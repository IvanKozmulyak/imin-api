package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.marketing.config.MarketingChatClient;
import com.imin.iminapi.marketing.dto.StoryKitDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The kit caption: the model writes only mood and hashtags, the server appends the facts line; no transaction spans the call. */
@Service
public class StoryKitCaptionService {

    private static final Logger log = LoggerFactory.getLogger(StoryKitCaptionService.class);

    static final int MAX_DESCRIPTION = 600;
    static final int MAX_BODY = 600;
    static final int MAX_HASHTAGS = 5;
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    // A bare domain such as imin.wtf or tickets.example: letters or digits, a dot, then two or more letters.
    private static final Pattern DOMAIN = Pattern.compile("[\\p{L}\\p{N}]\\.\\p{L}{2,}");
    private static final Map<String, String> LANGUAGE =
            Map.of("en", "English", "fr", "French", "es", "Spanish", "uk", "Ukrainian");

    /** The JSON the model is asked for. */
    public record CaptionLlm(String caption, List<String> hashtags) {}

    private final ChatClient chat;
    private final EventRepository events;
    private final StoryKitRepository kits;
    private final StoryKitService kitService;
    private final Clock clock;

    public StoryKitCaptionService(@Qualifier(MarketingChatClient.NAME) ChatClient chat, EventRepository events,
                                  StoryKitRepository kits, StoryKitService kitService, Clock clock) {
        this.chat = chat;
        this.events = events;
        this.kits = kits;
        this.kitService = kitService;
        this.clock = clock;
    }

    public StoryKitDto.Caption write(AuthPrincipal p, UUID eventId, int revision) {
        Event event = events.findActive(eventId).filter(e -> e.getOrgId().equals(p.orgId()))
                .orElseThrow(() -> ApiException.notFound("Event"));
        // Same gate as making the kit: no model call for a kit that cannot be posted.
        if (!kitService.isAvailable()) throw StoryKitService.unavailable();
        String reason = kitService.blockedReason(event);
        if (reason != null) throw StoryKitService.blocked(reason);
        StoryKit kit = kits.findByEventId(eventId).filter(k -> k.getOrgId().equals(p.orgId()))
                .orElseThrow(() -> ApiException.notFound("Story kit"));
        if (kit.getRevision() != revision) throw StoryKitService.kitChanged();

        String facts = factsLine(kit);
        Result r = compose(event, kit.getLocale(), facts);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int rows = kits.updateCaption(kit.getId(), revision, r.text(), r.ai(), now);
        if (rows == 0) throw StoryKitService.kitChanged();
        return new StoryKitDto.Caption(r.text(), r.ai(), now);
    }

    record Result(String text, boolean ai) {}

    /** Valid model text plus the server's facts line, or the deterministic caption. Never throws. */
    Result compose(Event event, String locale, String facts) {
        String known = knownFacts(event);
        Set<String> allowedNumbers = digitRuns(known);
        for (int attempt = 0; attempt < 2; attempt++) {
            CaptionLlm out;
            try {
                out = chat.prompt().user(prompt(known, locale)).call().entity(CaptionLlm.class);
            } catch (Exception e) {
                log.warn("Story kit caption model call failed; using the plain caption: {}", e.getMessage());
                break;
            }
            String text = accept(out, allowedNumbers, facts);
            if (text != null) return new Result(text, true);
        }
        return new Result(fallback(event, facts), false);
    }

    static String accept(CaptionLlm out, Set<String> allowedNumbers, String facts) {
        if (out == null || out.caption() == null) return null;
        String body = out.caption().trim();
        if (body.isEmpty() || body.length() > MAX_BODY) return null;
        List<String> tags = hashtags(out.hashtags());
        String all = body + " " + String.join(" ", tags);
        String lower = all.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("://") || lower.contains("www.") || lower.contains("@") || DOMAIN.matcher(all).find()) {
            return null;
        }
        if (!allowedNumbers.containsAll(digitRuns(all))) return null;
        StringBuilder sb = new StringBuilder(body).append("\n\n").append(facts);
        if (!tags.isEmpty()) sb.append("\n\n").append(String.join(" ", tags));
        return sb.toString();
    }

    static String fallback(Event event, String facts) {
        return event.getName().trim() + "\n\n" + facts;
    }

    static Set<String> digitRuns(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = DIGITS.matcher(s == null ? "" : s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static List<String> hashtags(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String t : raw) {
            if (t == null) continue;
            String word = t.replaceAll("[^\\p{L}\\p{N}]", "");
            if (word.isEmpty() || word.length() > 30) continue;
            String tag = "#" + word;
            if (!out.contains(tag)) out.add(tag);
            if (out.size() == MAX_HASHTAGS) break;
        }
        return out;
    }

    private static String knownFacts(Event e) {
        String desc = e.getDescription() == null ? "" : e.getDescription().trim();
        if (desc.length() > MAX_DESCRIPTION) desc = desc.substring(0, MAX_DESCRIPTION);
        return "Name: " + e.getName() + "\nGenre: " + e.getGenre() + "\nDescription: " + desc;
    }

    private static String prompt(String facts, String locale) {
        return """
                You write the Instagram caption for a night-out event. Write in %s.
                Use ONLY the facts below. Write one or two sentences about the mood and the music.
                Do NOT mention dates, times, the venue, prices, links, @handles, discounts or how many tickets are left;
                do NOT use any number that is not in the facts. Do not use urgency or scarcity wording.
                Reply with JSON only: {"caption": "...", "hashtags": ["...", "..."]} with at most %d hashtags, no # sign.

                %s
                """.formatted(LANGUAGE.getOrDefault(locale, "English"), MAX_HASHTAGS, facts);
    }

    private static String factsLine(StoryKit kit) {
        return StoryKitText.factsLine(kit.getSourceStartsAt(), StoryKitText.zone(kit.getSourceTimezone()), kit.getSourceVenueName(),
                kit.getSourceVenueCity(), kit.getLocale());
    }
}
