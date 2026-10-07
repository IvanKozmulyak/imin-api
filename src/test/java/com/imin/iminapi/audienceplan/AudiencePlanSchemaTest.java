package com.imin.iminapi.audienceplan;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Consent proof columns and the fan_features cascade on Postgres. */
@IminIntegrationTest
class AudiencePlanSchemaTest {

    @Autowired ConsentService consentService;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired FanFeatureRepository fanFeatureRepo;
    @Autowired JdbcTemplate jdbc;

    /** One capture overload, called for (org, membership, order id). */
    interface Capture {
        void run(ConsentService s, UUID orgId, UUID membershipId, UUID orderId);
    }

    private Membership seedMembership(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("plan-" + UUID.randomUUID() + "@example.com");
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m);
    }

    static Stream<Arguments> captures() {
        return Stream.of(
                Arguments.of("versioned with order id", (Capture) (s, org, mid, order) -> s.capture(org, mid,
                        "explicit", "checkout", "proof", "email", "checkout-named-v1", order, null),
                        "checkout", "checkout-named-v1", true),
                Arguments.of("channel overload", (Capture) (s, org, mid, order) -> s.capture(org, mid,
                        "explicit", "audience_ui", "proof", "email", null), "audience_ui", null, false),
                Arguments.of("default overload", (Capture) (s, org, mid, order) -> s.capture(org, mid,
                        "explicit", "audience_ui", "proof", null), "audience_ui", null, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("captures")
    void capture_storesTheTextVersionAndOrderIdOnlyWhenGiven(String name, Capture capture, String source,
                                                             String expectedVersion, boolean orderStored) {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();
        UUID orderId = UUID.randomUUID();

        capture.run(consentService, orgId, mid, orderId);

        List<ConsentRecord> rows = consentRepo.findByMembershipId(mid);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getTextVersion()).isEqualTo(expectedVersion);
            assertThat(r.getOrderId()).isEqualTo(orderStored ? orderId : null);
            assertThat(r.getChannel()).isEqualTo("email");
            assertThat(r.getLawfulBasis()).isEqualTo("explicit");
            assertThat(r.getSource()).isEqualTo(source);
            assertThat(r.getProofText()).isEqualTo("proof");
        });
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgId).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
    }

    @Test
    void deletingTheMembership_cascadesToFanFeatures() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();
        jdbc.update("insert into fan_features (membership_id, org_id, logic_version) values (?, ?, 1)",
                mid, orgId);

        membershipRepo.deleteByIdAndOrgId(mid, orgId);

        assertThat(fanFeatureRepo.findById(mid)).isEmpty();
    }
}
