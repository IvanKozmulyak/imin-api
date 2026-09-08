package com.imin.iminapi.service.ai;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * poster-2 regression: the FAILED staging row must survive the 502.
 *
 * <p>The pipeline used to run inside a single {@code @Transactional} method, so the
 * {@code status = FAILED} write in the catch block was rolled back by the very
 * {@link ApiException} it preceded — every failed generation left zero forensic trace.
 * Deliberately NOT {@code @Transactional}: a rollback-on-test would hide the bug.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class ConceptStudioFailurePersistenceTest {

    @Autowired ConceptStudioService studio;
    @Autowired GeneratedEventRepository repo;
    @Autowired OrganizationRepository orgs;

    @MockitoBean AiEventDescriptionService descService;
    @MockitoBean PosterOrchestrator orchestrator;
    @MockitoBean ConceptOverviewLlm overviewLlm;

    @Test
    void upstreamFailure_leaves_a_persisted_FAILED_generated_event_row() {
        when(descService.generateConcept(any(), anyLong(), anyBoolean()))
                .thenThrow(new IllegalStateException("ideogram unavailable"));

        Organization org = new Organization();
        org.setName("Poster Failure Org");
        org.setSlug("poster-failure-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@poster-failure.example");
        org.setCountry("DE");
        org = orgs.save(org);

        AuthPrincipal p = new AuthPrincipal(
                UUID.randomUUID(), org.getId(), UserRole.OWNER, UUID.randomUUID());

        assertThatThrownBy(() -> studio.create(p, new ConceptRequest(
                "Moody Berlin techno warehouse", "Techno", "Berlin", null, null,
                null, null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class);

        List<GeneratedEvent> rows = repo.findAll().stream()
                .filter(g -> p.orgId().equals(g.getOrgId()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(GeneratedEventStatus.FAILED);
    }
}
