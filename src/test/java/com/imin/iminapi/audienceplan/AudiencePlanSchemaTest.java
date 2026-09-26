package com.imin.iminapi.audienceplan;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Consent columns and the fan_features table on H2 (MODE=PostgreSQL). */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudiencePlanSchemaTest {

    @Autowired ConsentService consentService;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired FanFeatureRepository fanFeatureRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuditLogger auditLogger;

    private Membership seedMembership(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("plan-" + UUID.randomUUID() + "@example.com");
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m);
    }

    @Test
    void newMembership_objectedProfilingDefaultsFalse_inEntityAndColumn() {
        UUID orgId = UUID.randomUUID();
        Membership m = seedMembership(orgId);

        assertThat(membershipRepo.findByIdAndOrgId(m.getMembershipId(), orgId).orElseThrow()
                .isObjectedProfiling()).isFalse();
        // A row inserted without the column takes the database default.
        UUID rawId = UUID.randomUUID();
        jdbc.update("insert into memberships (membership_id, org_id, consumer_id) values (?, ?, ?)",
                rawId, UUID.randomUUID(), m.getConsumerId());
        try {
            assertThat(jdbc.queryForObject(
                    "select objected_profiling from memberships where membership_id = ?",
                    Boolean.class, rawId)).isFalse();
        } finally {
            jdbc.update("delete from memberships where membership_id = ?", rawId);
        }
    }

    @Test
    void objectedProfilingTrue_persists() {
        UUID orgId = UUID.randomUUID();
        Membership m = seedMembership(orgId);
        m.setObjectedProfiling(true);
        membershipRepo.save(m);

        assertThat(membershipRepo.findByIdAndOrgId(m.getMembershipId(), orgId).orElseThrow()
                .isObjectedProfiling()).isTrue();
    }

    @Test
    void capture_withTextVersionAndOrderId_persistsBoth() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();
        UUID orderId = UUID.randomUUID();

        consentService.capture(orgId, mid, "explicit", "checkout", "proof", "email",
                "checkout-named-v1", orderId, null);

        List<ConsentRecord> rows = consentRepo.findByMembershipId(mid);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getTextVersion()).isEqualTo("checkout-named-v1");
            assertThat(r.getOrderId()).isEqualTo(orderId);
            assertThat(r.getChannel()).isEqualTo("email");
            assertThat(r.getLawfulBasis()).isEqualTo("explicit");
            assertThat(r.getSource()).isEqualTo("checkout");
            assertThat(r.getProofText()).isEqualTo("proof");
        });
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgId).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
    }

    @Test
    void capture_channelOverloadWithoutVersion_keepsBothNull() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();

        consentService.capture(orgId, mid, "explicit", "audience_ui", "proof", "email", null);

        assertThat(consentRepo.findByMembershipId(mid)).singleElement().satisfies(r -> {
            assertThat(r.getTextVersion()).isNull();
            assertThat(r.getOrderId()).isNull();
        });
    }

    @Test
    void capture_defaultOverloadWithoutVersion_keepsBothNull() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();

        consentService.capture(orgId, mid, "explicit", "audience_ui", "proof", null);

        assertThat(consentRepo.findByMembershipId(mid)).singleElement().satisfies(r -> {
            assertThat(r.getTextVersion()).isNull();
            assertThat(r.getOrderId()).isNull();
        });
    }

    @Test
    void fanFeature_roundTripsEveryColumn() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();
        Instant first = Instant.parse("2026-01-10T19:00:00Z");
        Instant last = Instant.parse("2026-08-01T21:30:00Z");
        Instant contact = Instant.parse("2026-08-02T10:00:00Z");

        FanFeature f = new FanFeature();
        f.setMembershipId(mid);
        f.setOrgId(orgId);
        f.setPaidOrders(3);
        f.setFirstPaidPurchaseAt(first);
        f.setLastPaidPurchaseAt(last);
        f.setFanClass("loyal");
        f.setTaste("{\"house & techno\":1.0}");
        f.setCities("[\"metz\"]");
        f.setFormats("[\"club\"]");
        f.setNoShowN(1);
        f.setAvgGroupSize(new BigDecimal("1.667"));
        f.setSends30d(2);
        f.setLastContactFromPersonAt(contact);
        f.setLogicVersion(1);
        fanFeatureRepo.save(f);

        FanFeature back = fanFeatureRepo.findById(mid).orElseThrow();
        assertThat(back.getOrgId()).isEqualTo(orgId);
        assertThat(back.getPaidOrders()).isEqualTo(3);
        assertThat(back.getFirstPaidPurchaseAt()).isEqualTo(first);
        assertThat(back.getLastPaidPurchaseAt()).isEqualTo(last);
        assertThat(back.getFanClass()).isEqualTo("loyal");
        assertThat(back.getTaste()).isEqualTo("{\"house & techno\":1.0}");
        assertThat(back.getCities()).isEqualTo("[\"metz\"]");
        assertThat(back.getFormats()).isEqualTo("[\"club\"]");
        assertThat(back.getNoShowN()).isEqualTo(1);
        assertThat(back.getAvgGroupSize()).isEqualByComparingTo("1.667");
        assertThat(back.getSends30d()).isEqualTo(2);
        assertThat(back.getLastContactFromPersonAt()).isEqualTo(contact);
        assertThat(back.getLogicVersion()).isEqualTo(1);
        assertThat(back.getUpdatedAt()).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));

        // Column is literally named "class" and indexed with org_id.
        assertThat(jdbc.queryForObject("select class from fan_features where membership_id = ?",
                String.class, mid)).isEqualTo("loyal");
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.indexes where lower(index_name) = 'ix_fan_features_org_class'",
                Integer.class)).isPositive();
    }

    @Test
    void fanFeature_defaultsApplyToAMinimalRow() {
        UUID orgId = UUID.randomUUID();
        UUID mid = seedMembership(orgId).getMembershipId();

        jdbc.update("insert into fan_features (membership_id, org_id, logic_version) values (?, ?, 1)",
                mid, orgId);

        FanFeature back = fanFeatureRepo.findById(mid).orElseThrow();
        assertThat(back.getPaidOrders()).isZero();
        assertThat(back.getFanClass()).isEqualTo("none");
        assertThat(back.getNoShowN()).isZero();
        assertThat(back.getSends30d()).isZero();
        assertThat(back.getTaste()).isNull();
        assertThat(back.getAvgGroupSize()).isNull();
        assertThat(back.getUpdatedAt()).isNotNull();
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
