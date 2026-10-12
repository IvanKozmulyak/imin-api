package com.imin.iminapi.audienceplan;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceRetentionPurgeJob;
import com.imin.iminapi.audience.service.MembershipGraphDeleter;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.audienceplan.service.RetentionCutoff;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The SQL ops runs before the retention flips works on Postgres and counts what the purge job counts. */
@IminIntegrationTest
class RetentionCountsSqlPostgresTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired MarketingOptOutRepository optOutRepo;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired MembershipGraphDeleter deleter;
    @Autowired ConsentGate gate;
    @Autowired AudiencePlanLogic logic;
    @Autowired AuditLogger auditLogger;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        CampaignRows.delete(jdbc, orgIds);
        for (UUID org : orgIds) {
            List<UUID> consumerIds = jdbc.queryForList("select consumer_id from memberships where org_id = ?",
                    UUID.class, org);
            jdbc.update("delete from memberships where org_id = ?", org);
            jdbc.update("delete from marketing_optouts where org_id = ?", org);
            for (UUID c : consumerIds) jdbc.update("delete from consumers where consumer_id = ?", c);
        }
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void everyBlockRunsOnPostgres() throws Exception {
        List<String> blocks = sqlBlocks();

        assertThat(blocks).hasSize(4);
        for (String sql : blocks) jdbc.queryForList(sql);
    }

    @Test
    void membershipOptOutAndRecipientCounts_equalTheJobsDryRun() throws Exception {
        UUID org = org();
        Instant longAgo = Instant.now().minus(Duration.ofDays(1200));
        member(org, longAgo);
        member(org, Instant.now().minus(Duration.ofDays(5)));
        jdbc.update("insert into marketing_optouts (email_normalized, org_id, channel, source, created_at)"
                + " values (?, ?, 'email', 'footer_link', ?)", fx.email("optout"), org, Timestamp.from(longAgo));
        UUID campaign = UUID.randomUUID();
        jdbc.update("insert into campaigns (id, org_id, channel, name, status, sent_at, created_at)"
                + " values (?, ?, 'email', 'c', 'sent', ?, ?)", campaign, org, Timestamp.from(longAgo),
                Timestamp.from(longAgo));
        jdbc.update("insert into campaign_recipients (id, campaign_id, email, status) values (?, ?, ?, 'delivered')",
                UUID.randomUUID(), campaign, fx.email("rcpt"));

        AudienceRetentionPurgeJob.Result dryRun = job().run();
        List<String> blocks = sqlBlocks();

        assertThat(dryRun.membershipsExpired()).isEqualTo(1);
        assertThat(countFor(blocks.get(1), org)).isEqualTo(dryRun.membershipsExpired());
        assertThat(countFor(blocks.get(2), org)).isEqualTo(dryRun.optOutsExpired()).isEqualTo(1);
        assertThat(countFor(blocks.get(3), org)).isEqualTo(dryRun.recipientRowsExpired()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private AudienceRetentionPurgeJob job() {
        AudiencePlanProperties props = new AudiencePlanProperties();
        return new AudienceRetentionPurgeJob(membershipRepo, optOutRepo, recipientRepo, deleter,
                new RetentionCutoff(orgRepo, logic, Clock.systemUTC()), gate, props, auditLogger,
                mock(ObjectProvider.class), orgIds::contains);
    }

    private int countFor(String sql, UUID org) {
        return jdbc.queryForList(sql).stream()
                .filter(row -> org.equals(row.get("org_id")))
                .map(row -> ((Number) row.values().stream().filter(v -> v instanceof Number).findFirst()
                        .orElseThrow()).intValue())
                .findFirst().orElse(0);
    }

    private static List<String> sqlBlocks() throws Exception {
        String doc = Files.readString(Path.of("docs/ops/retention-counts.md"));
        Matcher m = Pattern.compile("```sql\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1).trim().replaceAll(";\\s*$", ""));
        return out;
    }

    private UUID org() {
        Organization o = fx.org();
        o.setTimezone("Europe/Paris");
        UUID id = orgRepo.save(o).getId();
        orgIds.add(id);
        return id;
    }

    private void member(UUID org, Instant createdAt) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(fx.email("counts"));
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(org);
        m.setConsumerId(c.getConsumerId());
        UUID id = membershipRepo.save(m).getMembershipId();
        jdbc.update("update memberships set created_at = ? where membership_id = ?", Timestamp.from(createdAt), id);
    }
}
