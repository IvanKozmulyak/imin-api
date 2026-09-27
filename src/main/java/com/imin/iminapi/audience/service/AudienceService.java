package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.AudienceMemberClass;
import com.imin.iminapi.audience.dto.MemberDto;
import com.imin.iminapi.audience.dto.MemberPage;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.repository.ConsentGateSql;
import com.imin.iminapi.audienceplan.service.AudienceReadModel;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.security.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates member list (keyset pagination) and member detail.
 */
@Service
public class AudienceService {

    private final MembershipRepository membershipRepo;
    private final ConsumerRepository consumerRepo;
    private final SuppressionRepository suppressionRepo;
    private final MemberListQuery memberListQuery;
    private final AudiencePlanAccess planAccess;
    private final AudiencePlanLogic planLogic;
    private final ConsentGate consentGate;
    private final AudienceReadModel readModel;

    public AudienceService(MembershipRepository membershipRepo,
                           ConsumerRepository consumerRepo,
                           SuppressionRepository suppressionRepo,
                           MemberListQuery memberListQuery,
                           AudiencePlanAccess planAccess,
                           AudiencePlanLogic planLogic,
                           ConsentGate consentGate,
                           AudienceReadModel readModel) {
        this.membershipRepo = membershipRepo;
        this.consumerRepo = consumerRepo;
        this.suppressionRepo = suppressionRepo;
        this.memberListQuery = memberListQuery;
        this.planAccess = planAccess;
        this.planLogic = planLogic;
        this.consentGate = consentGate;
        this.readModel = readModel;
    }

    /** List request as the controller received it; blank strings mean "not given". */
    public record MemberListRequest(String cursor, int limit, String lifecycle, String search, String sort,
                                    String guestClass, String genre, Boolean mailable) {}

    /**
     * Keyset-paginated member list, sorted by {@code sort} (default created_at), descending.
     * The guestClass, genre and mailable filters read fan_features and ConsentGate; they 404 while the
     * audience plan is switched off, like every other audience-plan surface.
     */
    @Transactional(readOnly = true)
    public MemberPage listMembers(UUID orgId, MemberListRequest req) {
        int pageSize = Math.min(Math.max(req.limit(), 1), 200);
        MemberListQuery.Sort sort = MemberListQuery.Sort.parse(req.sort());
        AudienceMemberClass guestClass = blank(req.guestClass()) ? null
                : AudienceMemberClass.parse(req.guestClass()).orElseThrow(() -> MemberListQuery.badRequest(
                        "guestClass", "must be one of loyal, repeat, first_timer, lapsing, dormant, imported, none"));
        String genre = blank(req.genre()) ? null : req.genre();
        if ((guestClass != null || genre != null || req.mailable() != null)) {
            planAccess.requireEnabled(orgId);
        }
        if (genre != null && !planLogic.genres().whitelist().contains(genre)) {
            throw MemberListQuery.badRequest("genre", "must be one of the 8 genre buckets");
        }
        MemberListQuery.Filter filter = new MemberListQuery.Filter(
                blank(req.lifecycle()) ? null : req.lifecycle(), req.search(), guestClass, genre, req.mailable(),
                req.mailable() == null ? null : ConsentGateSql.MAILABLE_IDS,
                req.mailable() == null ? Map.of() : consentGate.sqlParameters(orgId));
        MemberListQuery.Cursor cursor = blank(req.cursor()) ? null : MemberListQuery.Cursor.decode(req.cursor(), sort);

        List<Membership> rows = memberListQuery.page(orgId, filter, sort, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Membership> items = hasMore ? rows.subList(0, pageSize) : rows;

        List<MemberDto> dtos = withPlanFields(orgId, toMemberDtos(orgId, items));
        String nextCursor = hasMore ? MemberListQuery.Cursor.after(sort, items.get(items.size() - 1)).encode() : null;
        return new MemberPage(dtos, nextCursor);
    }

    /** Single member 360 view. Returns 404 (not 403) for cross-org access. */
    @Transactional(readOnly = true)
    public MemberDto getMember(UUID orgId, UUID membershipId) {
        Membership m = membershipRepo.findByIdAndOrgId(membershipId, orgId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
        Consumer c = consumerRepo.findAllByConsumerIdIn(List.of(m.getConsumerId()))
                .stream().findFirst().orElse(null);
        SuppressionEntry s = suppressionRepo
                .findMarketingByOrgAndMembership(orgId, membershipId).orElse(null);
        return withPlanFields(orgId, List.of(MemberDto.from(m, c, s))).get(0);
    }

    /**
     * Full filtered member list for CSV export. Applies the same lifecycle/search
     * filters as listMembers but returns all matching rows (no pagination), capped at
     * 100_000 to avoid OOM.
     */
    @Transactional(readOnly = true)
    public List<MemberDto> exportMembersCsv(UUID orgId, String lifecycle, String search) {
        int cap = 100_000;
        PageRequest page = PageRequest.of(0, cap);
        boolean hasSearch = search != null && !search.isBlank();
        List<Membership> rows = hasSearch
                ? membershipRepo.searchByOrg(orgId, lifecycle, search, page)
                : membershipRepo.listByOrg(orgId, lifecycle, page);

        List<UUID> consumerIds = rows.stream().map(Membership::getConsumerId).distinct().toList();
        Map<UUID, Consumer> consumerMap = consumerIds.isEmpty() ? Map.of()
                : consumerRepo.findAllByConsumerIdIn(consumerIds).stream()
                        .collect(Collectors.toMap(Consumer::getConsumerId, c -> c));

        Map<UUID, SuppressionEntry> suppressionMap = new HashMap<>();
        for (SuppressionEntry s : suppressionRepo.findMarketingByOrg(orgId)) {
            suppressionMap.put(s.getMembershipId(), s);
        }

        return rows.stream().map(m -> {
            Consumer c = consumerMap.get(m.getConsumerId());
            SuppressionEntry s = suppressionMap.get(m.getMembershipId());
            return MemberDto.from(m, c, s);
        }).toList();
    }

    /**
     * Convert a pre-resolved list of Membership rows to MemberDtos, batch-loading
     * consumers and suppressions. Used by the segment CSV export endpoint.
     */
    @Transactional(readOnly = true)
    public List<MemberDto> toMemberDtos(UUID orgId, List<Membership> memberships) {
        if (memberships.isEmpty()) return List.of();

        List<UUID> consumerIds = memberships.stream().map(Membership::getConsumerId).distinct().toList();
        Map<UUID, Consumer> consumerMap = consumerRepo.findAllByConsumerIdIn(consumerIds).stream()
                .collect(Collectors.toMap(Consumer::getConsumerId, c -> c));

        Map<UUID, SuppressionEntry> suppressionMap = new HashMap<>();
        for (SuppressionEntry s : suppressionRepo.findMarketingByOrg(orgId)) {
            suppressionMap.put(s.getMembershipId(), s);
        }

        return memberships.stream().map(m -> {
            Consumer c = consumerMap.get(m.getConsumerId());
            SuppressionEntry s = suppressionMap.get(m.getMembershipId());
            return MemberDto.from(m, c, s);
        }).toList();
    }

    /** Adds class, taste and sends30d; unchanged while the audience plan is switched off. */
    private List<MemberDto> withPlanFields(UUID orgId, List<MemberDto> dtos) {
        if (dtos.isEmpty() || !planAccess.isEnabled(orgId)) return dtos;
        Map<UUID, AudienceReadModel.MemberFields> fields = readModel.memberFields(orgId,
                dtos.stream().map(d -> UUID.fromString(d.membershipId())).toList());
        return dtos.stream().map(d -> {
            AudienceReadModel.MemberFields f = fields.get(UUID.fromString(d.membershipId()));
            return f == null ? d : d.withPlanFields(f.guestClass(), f.taste(), f.sends30d());
        }).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
