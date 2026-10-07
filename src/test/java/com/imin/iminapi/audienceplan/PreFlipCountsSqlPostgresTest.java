package com.imin.iminapi.audienceplan;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The ops pre-flip SQL runs on the migrated Postgres, and query 1 agrees with SendGate and ConsentGate. */
@IminIntegrationTest
class PreFlipCountsSqlPostgresTest {

    static final String NAMED_VERSION = "checkout-org-named-2026-09";

    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired SendGateService sendGate;
    @Autowired ConsentGate consentGate;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID org : orgIds) {
            List<UUID> consumerIds = jdbc.queryForList("select consumer_id from memberships where org_id = ?",
                    UUID.class, org);
            jdbc.update("delete from memberships where org_id = ?", org);
            for (UUID c : consumerIds) jdbc.update("delete from consumers where consumer_id = ?", c);
        }
        OrgRows.delete(jdbc, orgIds);
        orgIds.clear();
    }

    private static List<String> sqlBlocks() throws Exception {
        String doc = Files.readString(Path.of("docs/ops/pre-flip-counts.md"));
        Matcher m = Pattern.compile("```sql\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1).trim().replaceAll(";\\s*$", ""));
        return out;
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("PreFlip " + UUID.randomUUID().toString().substring(0, 6));
        o.setSlug("pf-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("pf@test.com");
        o.setCountry("FR");
        o.setTimezone("Europe/Paris");
        UUID id = orgs.save(o).getId();
        orgIds.add(id);
        return id;
    }

    private Membership member(UUID orgId, String consentStatus) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail("pf-" + UUID.randomUUID() + "@example.com");
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private Membership checkout(Membership m, String textVersion, Instant at) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(m.getMembershipId());
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("checkout");
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setOccurredAt(at);
        consentRecords.save(r);
        return m;
    }

    private Membership consent(Membership m, String source, String textVersion, boolean awaitingConfirmation,
                               Instant at) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(m.getMembershipId());
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource(source);
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setConfirmationRequired(awaitingConfirmation);
        r.setOccurredAt(at);
        consentRecords.save(r);
        return m;
    }

    @Test
    void everyBlockRunsOnPostgres() throws Exception {
        List<String> blocks = sqlBlocks();
        assertThat(blocks).hasSize(4);
        for (String sql : blocks) {
            jdbc.queryForList(sql);
        }
    }

    @Test
    void query1_matchesSendGateAndConsentGate() throws Exception {
        UUID orgId = org();
        Instant recent = Instant.now().minus(10, ChronoUnit.DAYS);
        List<Membership> all = List.of(
                checkout(member(orgId, "subscribed"), NAMED_VERSION, recent),
                checkout(member(orgId, "subscribed"), null, recent),
                member(orgId, "subscribed"),
                checkout(member(orgId, "subscribed"), NAMED_VERSION, Instant.now().minus(1200, ChronoUnit.DAYS)),
                checkout(member(orgId, "unsubscribed"), NAMED_VERSION, recent),
                // An unconfirmed door sign-up newer than a legacy import must not become the latest consent.
                consent(consent(member(orgId, "subscribed"), "organizer_import", null, false,
                        Instant.now().minus(20, ChronoUnit.DAYS)), "door_qr", "door-v1", true, recent));
        List<UUID> ids = all.stream().map(Membership::getMembershipId).toList();

        List<UUID> sendable = sendGate.evaluate(orgId, ids).sendable();
        Map<UUID, Optional<String>> verdicts = consentGate.reasons(orgId, sendable);
        Map<String, Object> row = jdbc.queryForList(sqlBlocks().get(0)).stream()
                .filter(r -> orgId.equals(r.get("org_id"))).findFirst().orElseThrow();

        assertThat(sendable).hasSize(5);
        assertThat(((Number) row.get("sendgate_mailable")).intValue()).isEqualTo(sendable.size());
        assertThat(((Number) row.get("mailable_after_flip")).intValue())
                .isEqualTo(1).isEqualTo(count(verdicts, null));
        assertThat(((Number) row.get("blocked_legacy_unproven")).intValue())
                .isEqualTo(2).isEqualTo(count(verdicts, ConsentGate.LEGACY_UNPROVEN));
        assertThat(((Number) row.get("blocked_no_basis")).intValue())
                .isEqualTo(1).isEqualTo(count(verdicts, ConsentGate.NO_BASIS));
        assertThat(((Number) row.get("blocked_retention_3y")).intValue())
                .isEqualTo(1).isEqualTo(count(verdicts, ConsentGate.RETENTION_3Y));
    }

    private static int count(Map<UUID, Optional<String>> verdicts, String reason) {
        return (int) verdicts.values().stream().filter(v -> v.orElse(null) == null
                ? reason == null : v.get().equals(reason)).count();
    }
}
