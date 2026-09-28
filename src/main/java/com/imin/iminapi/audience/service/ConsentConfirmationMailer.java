package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.ConsentConfirmationToken;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.marketing.render.OrganizerIdentity;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Sends the confirmation email after the sign-up commits, From {@code "<Organizer> via IMIN"}, no tracking.
 * A failed send never fails the sign-up; its row is removed so a later sign-up can retry.
 */
@Component
public class ConsentConfirmationMailer {

    private static final Logger log = LoggerFactory.getLogger(ConsentConfirmationMailer.class);

    static final String TEMPLATE = "consent-confirmation";

    private final ConsentConfirmationTokenRepository tokens;
    private final ConsentConfirmationTokenSigner signer;
    private final MembershipRepository memberships;
    private final ConsumerRepository consumers;
    private final OrganizationRepository orgs;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties props;
    // AFTER_COMMIT still sees the finished transaction bound, so the cleanup write needs its own.
    private final TransactionTemplate requiresNew;

    public ConsentConfirmationMailer(ConsentConfirmationTokenRepository tokens, ConsentConfirmationTokenSigner signer,
                                     MembershipRepository memberships, ConsumerRepository consumers,
                                     OrganizationRepository orgs, EmailService email,
                                     EmailTemplateRenderer renderer, EmailProperties props,
                                     PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.signer = signer;
        this.memberships = memberships;
        this.consumers = consumers;
        this.orgs = orgs;
        this.email = email;
        this.renderer = renderer;
        this.props = props;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ConsentConfirmationRequested e) {
        try {
            send(e);
        } catch (RuntimeException failure) {
            log.warn("Consent confirmation email failed for token={}: {}", e.tokenId(),
                    LogSafe.redact(failure.getMessage()));
            try {
                requiresNew.executeWithoutResult(status -> tokens.deleteById(e.tokenId()));
            } catch (RuntimeException cleanup) {
                log.warn("Consent confirmation token cleanup failed for token={}: {}", e.tokenId(),
                        cleanup.getMessage());
            }
        }
    }

    /** The buyer-site page the button opens; the token is base64url, so it needs no encoding. */
    public String confirmUrl(String token) {
        return props.getBuyerSiteBaseUrl() + "/consent/confirm?t=" + token;
    }

    private void send(ConsentConfirmationRequested e) {
        ConsentConfirmationToken t = tokens.findById(e.tokenId()).orElse(null);
        if (t == null) return;
        String to = memberships.findByIdAndOrgId(t.getMembershipId(), t.getOrgId())
                .flatMap(m -> consumers.findByConsumerId(m.getConsumerId()))
                .map(Consumer::getNormalizedEmail)
                .orElseThrow(() -> new IllegalStateException("No address for the confirmation email"));
        Organization org = orgs.findById(t.getOrgId())
                .orElseThrow(() -> new IllegalStateException("No organizer for the confirmation email"));
        String organizer = ConsentConfirmationService.organizerName(org);
        String locale = t.getLocale();
        var r = renderer.render(TEMPLATE, locale, Map.of(
                "organizer", organizer,
                "confirmUrl", confirmUrl(signer.sign(t.getId())),
                "expiresInDays", String.valueOf(ConsentConfirmationService.TTL.toDays())));
        String subject = OrganizerIdentity.singleLine(EmailLocale.choose(locale,
                "Confirm your email for " + organizer,
                "Confirma tu correo para " + organizer,
                "Confirmez votre e-mail pour " + organizer,
                "Підтвердьте email для " + organizer));
        email.sendFrom(props.fromHeader(organizer), to, subject, r.html(), r.text());
    }
}
