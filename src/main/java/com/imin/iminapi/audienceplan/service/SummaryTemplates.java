package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The code-written summary per locale, used whenever the model's text cannot be used. Every number comes from a
 * plan field; counts are shown as low–high ranges, never a lone middle value.
 */
final class SummaryTemplates {

    private SummaryTemplates() {}

    private record Words(
            Map<String, String> classLabel, Map<String, String> fit,
            String headlineWarm, String headlineCold, String gap, String gapNone, String segment,
            String invite, String inviteOn, String holdout, String and, String importWithProof, String rethinkTarget,
            String target, String ticketsPerOrder, String leftOut, String estimates) {}

    // Class and fit labels match the webapp's copy (events.audiencePlan.classLabel / fit).
    private static final Map<String, Words> WORDS = Map.of(
            "en", new Words(
                    Map.of("loyal", "Loyal", "repeat", "Repeat", "first_timer", "First-timers", "lapsing", "Lapsing",
                            "dormant", "Dormant", "imported", "Imported"),
                    Map.of("same", "same genre as this event", "adjacent", "close to this event’s genre",
                            "other", "mostly other genres", "unknown", "taste not known yet"),
                    "Your list could bring %s of the %s tickets you are aiming for.",
                    "There is no list you can email for this event yet; all %s tickets have to come from new people.",
                    "Still to find beyond your list: %s tickets.",
                    "At this estimate your list covers the target.",
                    "%s (%s): %s can be emailed, %s tickets expected.",
                    "Invite the %s group (%s).", "Invite the %s group (%s) on %s.",
                    " %s%% are held back to measure the effect.", " and ",
                    "Import contacts who agreed to hear from you, with proof of their consent.",
                    "The gap is larger than the local audience for this genre: consider a smaller room, another date"
                            + " or a stronger lineup.",
                    "Target: %s%% of %s tickets = %s.", "%s tickets per order.", "Left out: %s.",
                    "These are estimated ranges, not promises."),
            "es", new Words(
                    Map.of("loyal", "Fieles", "repeat", "Recurrentes", "first_timer", "Primera vez", "lapsing",
                            "En riesgo", "dormant", "Inactivos", "imported", "Importados"),
                    Map.of("same", "mismo género que este evento", "adjacent", "cercano al género de este evento",
                            "other", "sobre todo otros géneros", "unknown", "gustos aún desconocidos"),
                    "Tu lista podría aportar %s de las %s entradas de tu objetivo.",
                    "Aún no tienes una lista a la que escribir para este evento; las %s entradas tendrán que venir"
                            + " de gente nueva.",
                    "Faltan por encontrar fuera de tu lista: %s entradas.",
                    "Con esta estimación tu lista cubre el objetivo.",
                    "%s (%s): %s contactables, %s entradas previstas.",
                    "Invita al grupo %s (%s).", "Invita al grupo %s (%s) el %s.",
                    " Se reserva un %s %% para medir el efecto.", " y ",
                    "Importa contactos que aceptaron recibir tus mensajes, con prueba de su consentimiento.",
                    "La brecha supera al público local de este género: considera una sala más pequeña, otra fecha"
                            + " o un cartel más fuerte.",
                    "Objetivo: %s %% de %s entradas = %s.", "%s entradas por pedido.", "Fuera del plan: %s.",
                    "Son rangos estimados, no promesas."),
            "fr", new Words(
                    Map.of("loyal", "Fidèles", "repeat", "Réguliers", "first_timer", "Nouveaux", "lapsing",
                            "En perte", "dormant", "Inactifs", "imported", "Importés"),
                    Map.of("same", "même genre que cet événement", "adjacent", "proche du genre de cet événement",
                            "other", "surtout d’autres genres", "unknown", "goûts encore inconnus"),
                    "Votre liste pourrait apporter %s des %s billets visés.",
                    "Vous n’avez pas encore de liste à contacter pour cet événement ; les %s billets devront venir"
                            + " de nouvelles personnes.",
                    "Reste à trouver hors de votre liste : %s billets.",
                    "Selon cette estimation, votre liste couvre l’objectif.",
                    "%s (%s) : %s joignables, %s billets attendus.",
                    "Invitez le groupe %s (%s).", "Invitez le groupe %s (%s) le %s.",
                    " %s %% sont mis de côté pour mesurer l’effet.", " et ",
                    "Importez des contacts qui ont accepté vos messages, avec la preuve de leur consentement.",
                    "L’écart dépasse le public local de ce genre : envisagez une salle plus petite, une autre date"
                            + " ou une affiche plus forte.",
                    "Objectif : %s %% de %s billets = %s.", "%s billets par commande.", "Écartés : %s.",
                    "Ce sont des fourchettes estimées, pas des promesses."),
            "uk", new Words(
                    Map.of("loyal", "Лояльні", "repeat", "Повторні", "first_timer", "Нові", "lapsing", "Згасають",
                            "dormant", "Сплячі", "imported", "Імпортовані"),
                    Map.of("same", "той самий жанр, що й ця подія", "adjacent", "близько до жанру цієї події",
                            "other", "переважно інші жанри", "unknown", "смаки ще невідомі"),
                    "Ваш список може дати %s із %s квитків, які ви плануєте продати.",
                    "Для цієї події ще немає списку, якому можна написати; усі %s квитків мають прийти від нових"
                            + " людей.",
                    "Ще треба знайти поза вашим списком: %s квитків.",
                    "За цією оцінкою ваш список покриває ціль.",
                    "%s (%s): можна написати %s, очікувані квитки %s.",
                    "Запросіть групу «%s» (%s).", "Запросіть групу «%s» (%s) %s.",
                    " %s%% відкладаємо, щоб виміряти ефект.", " і ",
                    "Імпортуйте контакти, які погодилися отримувати ваші листи, з доказом їхньої згоди.",
                    "Розрив більший за місцеву аудиторію цього жанру: розгляньте менший зал, іншу дату або"
                            + " сильніший лайнап.",
                    "Ціль: %s%% від %s квитків = %s.", "Квитків на замовлення: %s.", "Не включено: %s.",
                    "Це оцінені діапазони, а не обіцянки."));

    static AudiencePlanResponse.Summary summary(AudiencePlanResponse p, String locale, Instant generatedAt) {
        Words w = WORDS.get(locale);
        if (w == null) throw new IllegalArgumentException("no summary template for locale " + locale);
        Locale loc = Locale.forLanguageTag(locale);

        String headline = p.expected() == null
                ? w.headlineCold().formatted(p.targetTickets())
                : w.headlineWarm().formatted(range(p.expected().low(), p.expected().high()), p.targetTickets());

        List<String> segmentLines = new ArrayList<>();
        for (AudiencePlanResponse.Segment s : p.segments()) {
            segmentLines.add(w.segment().formatted(label(w.classLabel(), s.classKey()), label(w.fit(), s.genreFit()),
                    s.mailable(), range(s.expected().low(), s.expected().high())));
        }

        String gapLine = p.gap().high() == 0 ? w.gapNone()
                : w.gap().formatted(range(p.gap().low(), p.gap().high()));

        List<String> actions = new ArrayList<>();
        for (AudiencePlanResponse.Action a : p.actions()) {
            actions.add(action(w, loc, a));
        }

        List<String> assumptions = new ArrayList<>();
        assumptions.add(w.target().formatted(p.assumptions().targetPct(), p.capacity(), p.targetTickets()));
        assumptions.add(w.ticketsPerOrder().formatted(decimal(p.assumptions().ticketsPerOrder(), loc)));
        List<String> excluded = p.assumptions().excludeSegments();
        if (excluded != null && !excluded.isEmpty()) {
            assumptions.add(w.leftOut().formatted(String.join(", ",
                    excluded.stream().map(c -> label(w.classLabel(), c)).toList())));
        }
        assumptions.add(w.estimates());

        return new AudiencePlanResponse.Summary(headline, List.copyOf(segmentLines), gapLine, List.copyOf(actions),
                List.copyOf(assumptions), locale, false, null, null, generatedAt);
    }

    private static String action(Words w, Locale loc, AudiencePlanResponse.Action a) {
        return switch (a.type()) {
            case "invite" -> {
                String cls = label(w.classLabel(), a.classKey());
                String fit = label(w.fit(), a.genreFit());
                List<String> dates = a.arms() == null ? List.of()
                        : a.arms().stream().map(d -> date(d.date(), loc)).toList();
                String line = dates.isEmpty() ? w.invite().formatted(cls, fit)
                        : w.inviteOn().formatted(cls, fit, String.join(w.and(), dates));
                // 0 = the group is too small for a holdout.
                yield a.holdoutPct() == null || a.holdoutPct() == 0 ? line : line + w.holdout().formatted(a.holdoutPct());
            }
            case "import_with_proof" -> w.importWithProof();
            case "rethink_target" -> w.rethinkTarget();
            default -> throw new IllegalArgumentException("no summary template for action " + a.type());
        };
    }

    private static String label(Map<String, String> labels, String key) {
        return key == null ? "" : labels.getOrDefault(key, key);
    }

    static String range(int low, int high) {
        return low == high ? Integer.toString(low) : low + "–" + high;
    }

    private static String decimal(double v, Locale loc) {
        DecimalFormat f = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(loc));
        return f.format(v);
    }

    private static String date(LocalDate d, Locale loc) {
        return d == null ? "" : d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(loc));
    }
}
