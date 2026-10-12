package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.EventCancellationRepository.NotifyClaim;
import com.imin.iminapi.cancellation.EventCancellationRepository.NotifyFacts;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.util.LogSafe;
import com.imin.iminapi.util.MoneyFormat;
import com.imin.iminapi.util.OrderNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells each buyer of a cancelled event what happens to their order, once, in the language they bought in.
 * At most once per claim: a crash between the send and the mark resends after the claim goes stale.
 */
@Component
public class EventCancellationEmailer {

    private static final Logger log = LoggerFactory.getLogger(EventCancellationEmailer.class);

    static final String TEMPLATE = "event-cancelled-buyer";
    static final String FALLBACK_CONTACT = "hello@imin.wtf";
    static final Duration STALE_CLAIM = Duration.ofMinutes(30);
    static final int MAX_ATTEMPTS = 5;
    private static final String NBSP = " ";

    /** What the refund paragraph says, from the order's live facts. */
    enum Variant { FREE, DISPUTED, ALREADY, REMAINDER, FULL }

    private final EventCancellationRepository items;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public EventCancellationEmailer(EventCancellationRepository items, EmailService email,
                                    EmailTemplateRenderer renderer, StripeProperties stripeProps, Clock clock) {
        this.items = items;
        this.email = email;
        this.renderer = renderer;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    /** Sends up to {@code limit} notices; returns how many went out. */
    public int notifyNext(int limit) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<NotifyClaim> claimed = items.claimNotify(limit, now, now.minus(STALE_CLAIM), !stripeProps.isLiveKey());
        int sent = 0;
        for (NotifyClaim c : claimed) {
            try {
                if (notifyOne(c.orderId(), now)) sent++;
            } catch (RuntimeException e) {
                failed(c, e, now);
            }
        }
        return sent;
    }

    private boolean notifyOne(UUID orderId, Instant now) {
        NotifyFacts f = items.notifyFacts(orderId).orElse(null);
        if (f == null) return false;
        if (f.email() == null || f.email().isBlank()) {
            items.markNotified(orderId, "no_address", now);
            return false;
        }
        String eventName = f.eventName() == null ? "" : f.eventName();
        String orderNumber = OrderNumber.display(orderId);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName);
        values.put("refundParagraph", refundParagraph(f, orderNumber));
        values.put("contactLine", contactLine(f.locale(), f.organizerContact()));
        EmailTemplateRenderer.Rendered r = renderer.render(TEMPLATE, f.locale(), values);
        String subject = EmailLocale.choose(f.locale(),
                "Cancelled: " + eventName + " · order " + orderNumber,
                "Cancelado: " + eventName + " · pedido " + orderNumber,
                "Annulé" + NBSP + ": " + eventName + " · commande " + orderNumber,
                "Скасовано: " + eventName + " · замовлення " + orderNumber);
        email.send(f.email(), subject, r.html(), r.text());
        items.markNotified(orderId, "sent", now);
        log.info("[cancellation-email] sent orderId={} to {}", orderId, LogSafe.email(f.email()));
        return true;
    }

    private void failed(NotifyClaim c, RuntimeException e, Instant now) {
        // The bookkeeping write is wrapped, so its own failure never hides the send failure.
        try {
            if (c.attempts() >= MAX_ATTEMPTS) {
                items.markNotified(c.orderId(), "failed", now);
                log.error("[CANCELLATION_EMAIL_FAILED] orderId={} attempts={}", c.orderId(), c.attempts(), e);
            } else {
                items.releaseNotify(c.orderId());
                log.warn("[cancellation-email] send failed orderId={} attempt={}", c.orderId(), c.attempts(), e);
            }
        } catch (RuntimeException write) {
            write.addSuppressed(e);
            log.error("[cancellation-email] could not record the failed send orderId={}", c.orderId(), write);
        }
    }

    /** Names the organizer only when there is an address to write to; otherwise imin alone. */
    static String contactLine(String locale, String organizerContact) {
        if (organizerContact == null || organizerContact.isBlank()) {
            return EmailLocale.choose(locale,
                    "Questions? Contact us at " + FALLBACK_CONTACT + ", with your order number.",
                    "¿Preguntas? Escríbenos a " + FALLBACK_CONTACT + " con tu número de pedido.",
                    "Des questions" + NBSP + "? Écrivez-nous à " + FALLBACK_CONTACT + ", avec votre numéro de commande.",
                    "Є питання? Напишіть нам на " + FALLBACK_CONTACT + " і вкажіть номер замовлення.");
        }
        String c = organizerContact.trim();
        return EmailLocale.choose(locale,
                "Questions? Contact the organizer at " + c + " or " + FALLBACK_CONTACT + ", with your order number.",
                "¿Preguntas? Escribe al organizador (" + c + ") o a " + FALLBACK_CONTACT + " con tu número de pedido.",
                "Des questions" + NBSP + "? Écrivez à l’organisateur (" + c + ") ou à " + FALLBACK_CONTACT
                        + ", avec votre numéro de commande.",
                "Є питання? Напишіть організатору (" + c + ") або на " + FALLBACK_CONTACT
                        + " і вкажіть номер замовлення.");
    }

    static Variant variant(NotifyFacts f) {
        if (f.totalMinor() == 0) return Variant.FREE;
        if (f.disputes() > 0) return Variant.DISPUTED;
        if (f.totalMinor() - f.earlierRefundedMinor() <= 0) return Variant.ALREADY;
        if (f.earlierRefundedMinor() > 0) return Variant.REMAINDER;
        return Variant.FULL;
    }

    /** Chosen per locale here so each variant reads as one sentence in every language. */
    static String refundParagraph(NotifyFacts f, String orderNumber) {
        String l = f.locale();
        String amount = MoneyFormat.format(Math.max(0, f.totalMinor() - f.earlierRefundedMinor()), f.currency());
        String rest = EmailLocale.choose(l,
                " The refund goes to the payment method you used. imin starts it within 5 working days and your "
                        + "bank shows it within 5 to 10 days. You will get another email once it is done.",
                " El reembolso va al medio de pago que usaste. imin lo inicia en un plazo de 5 días hábiles y tu "
                        + "banco lo muestra en 5 a 10 días. Recibirás otro correo cuando se haya hecho.",
                " Le remboursement est fait sur le moyen de paiement utilisé. imin le déclenche dans les 5 jours "
                        + "ouvrés et il apparaît sur votre relevé sous 5 à 10 jours selon votre banque. Vous recevrez "
                        + "un autre e-mail une fois le remboursement effectué.",
                " Кошти надійдуть на той спосіб оплати, яким ви платили. imin ініціює повернення протягом "
                        + "5 робочих днів, а ваш банк покаже його за 5–10 днів. Коли повернення буде виконано, ви "
                        + "отримаєте ще один лист.");
        return switch (variant(f)) {
            case FULL -> EmailLocale.choose(l,
                    "You will get back " + amount + " for order " + orderNumber
                            + ": everything you paid, booking fee included.",
                    "Te devolveremos " + amount + " del pedido " + orderNumber
                            + ": todo lo que pagaste, gastos de gestión incluidos.",
                    "Vous serez remboursé de " + amount + " pour la commande " + orderNumber + NBSP
                            + ": la totalité de votre paiement, frais de réservation compris.",
                    "Вам повернуть " + amount + " за замовлення " + orderNumber
                            + ": усю сплачену суму разом із сервісним збором.") + rest;
            case REMAINDER -> EmailLocale.choose(l,
                    "You will get back " + amount + " for order " + orderNumber
                            + ": the part not refunded yet, booking fee included.",
                    "Te devolveremos " + amount + " del pedido " + orderNumber
                            + ": la parte aún no reembolsada, gastos de gestión incluidos.",
                    "Vous serez remboursé de " + amount + " pour la commande " + orderNumber + NBSP
                            + ": la part pas encore remboursée, frais de réservation compris.",
                    "Вам повернуть " + amount + " за замовлення " + orderNumber
                            + ": частину, яку ще не повернули, разом із сервісним збором.") + rest;
            case ALREADY -> EmailLocale.choose(l,
                    "The full amount of order " + orderNumber
                            + " has already been refunded or is being refunded, so nothing more is due.",
                    "El importe total del pedido " + orderNumber
                            + " ya se ha reembolsado o se está reembolsando, así que no queda nada pendiente.",
                    "Le montant total de la commande " + orderNumber
                            + " a déjà été remboursé ou est en cours de remboursement" + NBSP + ": rien d'autre n'est dû.",
                    "Повну суму за замовлення " + orderNumber
                            + " вже повернуто або повертається, тож більше нічого не належить.");
            case FREE -> EmailLocale.choose(l,
                    "Order " + orderNumber + " was free, so there is nothing to refund.",
                    "El pedido " + orderNumber + " era gratuito, así que no hay nada que reembolsar.",
                    "La commande " + orderNumber + " était gratuite" + NBSP + ": il n'y a rien à rembourser.",
                    "Замовлення " + orderNumber + " було безкоштовним, тож повертати нічого.");
            case DISPUTED -> EmailLocale.choose(l,
                    "There is a payment dispute with your bank on order " + orderNumber
                            + ", so your refund follows the bank's decision.",
                    "Hay una disputa de pago con tu banco sobre el pedido " + orderNumber
                            + ", así que tu reembolso depende de la decisión del banco.",
                    "Votre commande " + orderNumber + " fait l'objet d'un litige de paiement auprès de votre banque"
                            + NBSP + ": votre remboursement suit la décision de la banque.",
                    "Щодо замовлення " + orderNumber
                            + " є платіжний спір із вашим банком, тому повернення коштів залежить від рішення банку.");
        };
    }
}
