package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.repository.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Short codes behind app.imin.wtf/s/{code}: one per event and purpose, kept across kit remakes. */
@Service
public class ShareLinkService {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int CODE_LENGTH = 7;
    private static final Pattern VALID_CODE = Pattern.compile("^[0-9A-Za-z]{7,12}$");
    private static final int MAX_ATTEMPTS = 6;

    private final ShareLinkRepository links;
    private final EventRepository events;
    private final MarketingEmailProperties props;
    private final TransactionTemplate insertTx;
    private final Supplier<String> codes;

    @Autowired
    public ShareLinkService(ShareLinkRepository links, EventRepository events, MarketingEmailProperties props,
                            PlatformTransactionManager transactionManager) {
        this(links, events, props, transactionManager, randomCodes());
    }

    // Tests hand in the codes to force a collision.
    ShareLinkService(ShareLinkRepository links, EventRepository events, MarketingEmailProperties props,
                     PlatformTransactionManager transactionManager, Supplier<String> codes) {
        this.links = links;
        this.events = events;
        this.props = props;
        this.codes = codes;
        this.insertTx = new TransactionTemplate(transactionManager);
        this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The link a public tap resolves to: the event id and the tags it carries. */
    public record Resolved(UUID eventId, String utmSource, String utmMedium, String utmCampaign) {}

    /** Existing link for the event's story kit, or a new one. Safe against a concurrent first call. */
    public ShareLink getOrCreateForStoryKit(UUID orgId, UUID eventId, UUID userId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<ShareLink> existing = links.findByEventIdAndPurpose(eventId, ShareLink.PURPOSE_STORY_KIT);
            if (existing.isPresent()) return existing.get();
            String code = codes.get();
            ShareLink link = ShareLink.create();
            link.setCode(code);
            link.setOrgId(orgId);
            link.setEventId(eventId);
            link.setPurpose(ShareLink.PURPOSE_STORY_KIT);
            link.setUtmSource("instagram");
            link.setUtmMedium("story");
            link.setUtmCampaign("kit_" + code);
            link.setCreatedBy(userId);
            try {
                return insertTx.execute(s -> links.saveAndFlush(link));
            } catch (DataIntegrityViolationException collisionOrRace) {
                // Either the code collided or a concurrent call created the event's link: look again.
            }
        }
        throw new IllegalStateException("Could not allocate a share link code");
    }

    /** The link and its event, only while the event is public (the same rule as the public event page). */
    public Optional<Resolved> resolve(String code) {
        if (code == null || !VALID_CODE.matcher(code).matches()) return Optional.empty();
        return links.findById(code)
                .filter(l -> events.findPublic(l.getEventId()).isPresent())
                .map(l -> new Resolved(l.getEventId(), l.getUtmSource(), l.getUtmMedium(), l.getUtmCampaign()));
    }

    public String urlFor(String code) {
        String base = props.getBuyerSiteBaseUrl();
        String root = base == null ? "" : base.replaceAll("/+$", "");
        return root + "/s/" + code;
    }

    private static Supplier<String> randomCodes() {
        SecureRandom random = new SecureRandom();
        return () -> {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            return sb.toString();
        };
    }
}
