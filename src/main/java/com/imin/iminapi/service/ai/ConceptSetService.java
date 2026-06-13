package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.*;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Text-only generation of THREE distinct event concepts (name + description + IG/TikTok/X
 * captions + suggested genre/type/capacity/tiers), conditioned on the org brand. This is a
 * lightweight LLM path that never touches the poster pipeline ({@code PosterOrchestrator}/Ideogram).
 */
@Service
public class ConceptSetService {

    private static final Logger log = LoggerFactory.getLogger(ConceptSetService.class);
    private static final int WANT = 3;
    private static final int MAX_ATTEMPTS = 2;

    private final ChatClient chat;
    private final OrganizationRepository orgs;
    private final PricingService pricing;
    private final GeneratedEventRepository repo;

    public ConceptSetService(ChatClient chat, OrganizationRepository orgs,
                             PricingService pricing, GeneratedEventRepository repo) {
        this.chat = chat;
        this.orgs = orgs;
        this.pricing = pricing;
        this.repo = repo;
    }

    @Transactional
    public ConceptSetResponse create(AuthPrincipal p, ConceptSetRequest req) {
        String brandBlock = brandBlock(p.orgId());
        ConceptSet set = generateWithRetry(req, brandBlock);

        PricingRecommendation prices = pricing.recommend(
                req.genre() == null ? "techno" : req.genre(),
                req.city() == null ? "" : req.city(),
                LocalDate.now().plusMonths(2));

        GeneratedEvent staging = new GeneratedEvent();
        staging.setOrgId(p.orgId());
        staging.setVibe(req.vibe());
        staging.setGenre(req.genre());
        staging.setCity(req.city());
        staging.setTone("energetic");
        staging.setEventDate(LocalDate.now().plusMonths(2));
        staging.setPlatforms("instagram,tiktok,x");
        staging.setStatus(GeneratedEventStatus.DRAFT);

        // Build the cards in sortOrder order and attach a Concept row per card (same order),
        // so the persisted child ids align with the cards by index after the flush below.
        List<ConceptCardDto> cards = new ArrayList<>();
        int sort = 0;
        for (ConceptSet.LlmConcept c : set.concepts()) {
            ConceptSet.LlmCaptions caps = c.captions();
            String ig = caps == null ? null : caps.instagram();
            String tt = caps == null ? null : caps.tiktok();
            String x = caps == null ? null : caps.x();

            Concept entity = new Concept();
            entity.setGeneratedEvent(staging);
            entity.setTitle(c.name());
            entity.setDescription(c.description());
            entity.setSortOrder(sort);
            entity.setInstagramCaption(ig);
            entity.setTiktokCaption(tt);
            entity.setXCaption(x);
            staging.getConcepts().add(entity);

            List<SuggestedTierDto> tiers = buildTiers(prices, c.suggestedCapacity());
            cards.add(new ConceptCardDto(null, c.name(), c.description(),
                    new CaptionsDto(ig, tt, x),
                    c.suggestedGenre(), c.suggestedType(), c.suggestedCapacity(), tiers));
            sort++;
        }
        staging.setStatus(GeneratedEventStatus.COMPLETE);
        // saveAndFlush so the cascade-persisted Concept rows get their generated UUIDs assigned now;
        // the response must carry the persisted conceptIds.
        repo.saveAndFlush(staging);

        // Re-emit cards with the persisted Concept ids (sortOrder-aligned by insertion index).
        List<ConceptCardDto> withIds = new ArrayList<>();
        List<Concept> saved = staging.getConcepts();
        for (int i = 0; i < cards.size(); i++) {
            UUID id = i < saved.size() ? saved.get(i).getId() : null;
            ConceptCardDto base = cards.get(i);
            withIds.add(new ConceptCardDto(id, base.name(), base.description(), base.captions(),
                    base.suggestedGenre(), base.suggestedType(), base.suggestedCapacity(),
                    base.suggestedTiers()));
        }
        return new ConceptSetResponse(staging.getId(), withIds);
    }

    private ConceptSet generateWithRetry(ConceptSetRequest req, String brandBlock) {
        ConceptSet last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ConceptSet set = chat.prompt().user(buildPrompt(req, brandBlock)).call().entity(ConceptSet.class);
                if (set != null && set.concepts() != null && set.concepts().size() >= WANT
                        && set.concepts().stream().limit(WANT).allMatch(c ->
                            c.name() != null && !c.name().isBlank()
                            && c.description() != null && !c.description().isBlank())) {
                    return new ConceptSet(new ArrayList<>(set.concepts().subList(0, WANT)));
                }
                last = set;
            } catch (Exception e) {
                log.warn("Concept-set generation attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        if (last != null && last.concepts() != null && !last.concepts().isEmpty()) {
            log.warn("Concept-set returned fewer than {} valid concepts; best-effort.", WANT);
            return last;
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                "Concept generation service unavailable");
    }

    private String buildPrompt(ConceptSetRequest req, String brandBlock) {
        return """
                You are an expert event marketer naming and describing events for a ticketing app.
                Generate exactly THREE DISTINCT event concepts for the same brief — each a different
                creative angle (e.g. one moody/underground, one bold/hype, one intimate/curated).
                Return JSON ONLY matching this schema:

                {
                  "concepts": [
                    {
                      "name": "<short evocative event name, max 6 words>",
                      "description": "<one paragraph, 30-60 words, second person>",
                      "captions": {
                        "instagram": "<Instagram caption, 1-2 sentences + 2-4 hashtags>",
                        "tiktok": "<punchy TikTok caption, 1 sentence + trending-style hashtags>",
                        "x": "<concise X/Twitter post, <=200 chars>"
                      },
                      "suggested_genre": "<music genre>",
                      "suggested_type": "<one of: Festival, Rave, Club, Concert, Open Air>",
                      "suggested_capacity": <integer 50-2000>
                    }
                  ]
                }

                The three concepts must be meaningfully different from each other.
                %s
                Vibe: %s
                Genre: %s
                City: %s
                Capacity hint: %s
                """.formatted(
                        brandBlock,
                        req.vibe(),
                        req.genre() == null ? "(unspecified)" : req.genre(),
                        req.city() == null ? "(unspecified)" : req.city(),
                        req.capacity() == null ? "(unspecified)" : req.capacity().toString());
    }

    /** Brand conditioning block, failure-isolated: brandless org → empty string. */
    private String brandBlock(UUID orgId) {
        try {
            Organization o = orgs.findById(orgId).orElse(null);
            if (o == null) return "";
            List<String> colors = o.getBrandAccentColors() == null ? List.of() : o.getBrandAccentColors();
            boolean blank = (o.getBrandName() == null || o.getBrandName().isBlank()) && colors.isEmpty();
            if (blank) return "";
            StringBuilder sb = new StringBuilder("BRAND (match this identity's voice and palette):\n");
            if (o.getBrandName() != null && !o.getBrandName().isBlank()) {
                sb.append("Brand name: ").append(o.getBrandName()).append('\n');
            }
            if (!colors.isEmpty()) {
                sb.append("Brand colors: ").append(String.join(", ", colors)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Brand lookup failed; generating brandless: {}", e.getMessage());
            return "";
        }
    }

    /** Mirrors ConceptStudioService.buildTiers — three tiers from a pricing recommendation + capacity. */
    private static List<SuggestedTierDto> buildTiers(PricingRecommendation prices, Integer capacity) {
        BigDecimal min = prices.suggestedMinPrice() == null ? new BigDecimal("12") : prices.suggestedMinPrice();
        BigDecimal max = prices.suggestedMaxPrice() == null ? new BigDecimal("24") : prices.suggestedMaxPrice();
        BigDecimal mid = min.add(max).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        int cap = capacity == null ? 250 : capacity;
        return List.of(
                new SuggestedTierDto("Early Bird", min.movePointRight(2).intValueExact(), Math.max(1, cap / 5)),
                new SuggestedTierDto("Standard",   mid.movePointRight(2).intValueExact(), Math.max(1, cap * 3 / 5)),
                new SuggestedTierDto("Door",       max.movePointRight(2).intValueExact(), Math.max(1, cap / 5)));
    }
}
