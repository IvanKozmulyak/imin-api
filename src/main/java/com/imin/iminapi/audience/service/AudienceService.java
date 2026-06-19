package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.MemberDto;
import com.imin.iminapi.audience.dto.MemberPage;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.security.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    public AudienceService(MembershipRepository membershipRepo,
                           ConsumerRepository consumerRepo,
                           SuppressionRepository suppressionRepo) {
        this.membershipRepo = membershipRepo;
        this.consumerRepo = consumerRepo;
        this.suppressionRepo = suppressionRepo;
    }

    /**
     * Keyset-paginated member list. Cursor encodes (createdAt, membershipId).
     * Sort: created_at DESC, membership_id DESC (both non-null — S2).
     */
    @Transactional(readOnly = true)
    public MemberPage listMembers(UUID orgId, String cursor, int limit,
                                   String lifecycle, String search) {
        int pageSize = Math.min(Math.max(limit, 1), 200);
        PageRequest page = PageRequest.of(0, pageSize + 1); // fetch one extra to detect next page

        List<Membership> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = membershipRepo.listByOrg(orgId, lifecycle, search, page);
        } else {
            CursorValue cv = CursorValue.decode(cursor);
            rows = membershipRepo.listByOrgAfterCursor(orgId, lifecycle, search,
                    cv.createdAt(), cv.membershipId(), page);
        }

        boolean hasMore = rows.size() > pageSize;
        List<Membership> items = hasMore ? rows.subList(0, pageSize) : rows;

        // Batch-load consumers
        List<UUID> consumerIds = items.stream().map(Membership::getConsumerId).distinct().toList();
        Map<UUID, Consumer> consumerMap = consumerIds.isEmpty() ? Map.of()
                : consumerRepo.findAllByConsumerIdIn(consumerIds).stream()
                        .collect(Collectors.toMap(Consumer::getConsumerId, c -> c));

        // Batch-load marketing suppressions for these memberships
        List<UUID> membershipIds = items.stream().map(Membership::getMembershipId).toList();
        Map<UUID, SuppressionEntry> suppressionMap = new HashMap<>();
        if (!membershipIds.isEmpty()) {
            for (SuppressionEntry s : suppressionRepo.findMarketingByOrg(orgId)) {
                if (membershipIds.contains(s.getMembershipId())) {
                    suppressionMap.put(s.getMembershipId(), s);
                }
            }
        }

        List<MemberDto> dtos = items.stream().map(m -> {
            Consumer c = consumerMap.get(m.getConsumerId());
            SuppressionEntry s = suppressionMap.get(m.getMembershipId());
            return MemberDto.from(m, c, s);
        }).toList();

        String nextCursor = null;
        if (hasMore) {
            Membership last = items.get(items.size() - 1);
            nextCursor = CursorValue.encode(last.getCreatedAt(), last.getMembershipId());
        }

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
        return MemberDto.from(m, c, s);
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
        List<Membership> rows = membershipRepo.listByOrg(orgId, lifecycle, search, page);

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

    /** Cursor encoding: base64(createdAt.epochMilli + "," + membershipId) */
    record CursorValue(Instant createdAt, UUID membershipId) {
        static String encode(Instant at, UUID id) {
            String raw = at.toEpochMilli() + "," + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
        }
        static CursorValue decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor));
                String[] parts = raw.split(",", 2);
                return new CursorValue(Instant.ofEpochMilli(Long.parseLong(parts[0])),
                        UUID.fromString(parts[1]));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
