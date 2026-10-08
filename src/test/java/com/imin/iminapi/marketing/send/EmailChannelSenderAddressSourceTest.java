package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Art. 14: the first email to an imported member names the source of their address; later ones do not. */
@IminIntegrationTest
class EmailChannelSenderAddressSourceTest {

    private static final String LINE = "This organizer received your address from ";
    private static final String PRIVACY = "https://app.imin.wtf/legal/privacy";

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired AudienceImportRepository imports;
    @Autowired ImportRowProvenanceRepository provenance;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    /** The campaigns are left 'sending', which the global claim would reclaim once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private record Member(UUID id, String email) {}

    private Member member(UUID orgId) {
        Consumer cn = new Consumer();
        String email = fx.email("src");
        cn.setNormalizedEmail(email);
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return new Member(memberships.save(m).getMembershipId(), email);
    }

    private void imported(UUID importOrgId, Member m, String platform, boolean accepted, Instant at) {
        AudienceImport imp = new AudienceImport();
        imp.setOrgId(importOrgId);
        imp = imports.save(imp);
        ImportRowProvenance p = new ImportRowProvenance();
        p.setImportId(imp.getId());
        p.setMembershipId(m.id());
        p.setRowNumber(1);
        p.setSourcePlatform(platform);
        p.setExportDate(LocalDate.parse("2026-09-01"));
        p.setMarketingStatus("opted_in");
        p.setProofRef(accepted ? "export.csv" : null);
        p.setAccepted(accepted);
        p.setRejectReason(accepted ? null : "missing_proof");
        p.setCreatedAt(at);
        provenance.save(p);
    }

    private Campaign campaign(UUID orgId, String channel, String status) {
        orgIds.add(orgId);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel(channel);
        c.setName("Blast");
        c.setStatus(status);
        c.setSubject("Subject");
        c.setBodyMd("Hello");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private void row(Campaign c, Member m, String status) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.id());
        r.setEmail(m.email());
        r.setStatus(status);
        r.setLastEventAt(Instant.now().minusSeconds(86_400L * 40));
        recipients.save(r);
    }

    @SuppressWarnings("unchecked")
    private String sendOneAndCaptureHtml(Campaign c, Member m) {
        clearInvocations(provider);
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());
        sender.sendNextBatch(c);
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        List<CampaignEmailProvider.OutgoingEmail> mine = captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> m.email().equals(e.to())).toList();
        assertThat(mine).hasSize(1);
        CampaignEmailProvider.OutgoingEmail e = mine.get(0);
        return e.html() + "\n" + e.text();
    }

    @Test
    void firstEmailToImportedMember_hasTheSourceLine_theSecondDoesNot() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        imported(orgId, m, "Shotgun", true, Instant.now());

        Campaign first = campaign(orgId, "email", "sending");
        row(first, m, "pending");
        String firstMail = sendOneAndCaptureHtml(first, m);
        assertThat(firstMail).contains(LINE + "Shotgun. IMIN processes it on the organizer&#39;s behalf; see the <a href=\"")
                .contains(PRIVACY + "\"").contains(">privacy notice</a> for retention and your rights.")
                .contains(LINE + "Shotgun. IMIN processes it on the organizer's behalf; retention and your rights: " + PRIVACY + "\n");
        assertThat(recipients.countByCampaignIdAndStatus(first.getId(), "sent")).isEqualTo(1L);

        Campaign second = campaign(orgId, "email", "sending");
        row(second, m, "pending");
        assertThat(sendOneAndCaptureHtml(second, m)).doesNotContain(LINE);
    }

    @Test
    void memberWithoutImportProvenance_neverGetsTheLine() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        Campaign c = campaign(orgId, "email", "sending");
        row(c, m, "pending");
        assertThat(sendOneAndCaptureHtml(c, m)).doesNotContain(LINE);
    }

    @Test
    void rejectedProvenanceRow_doesNotMakeTheMemberImported() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        imported(orgId, m, "Dice", false, Instant.now());
        Campaign c = campaign(orgId, "email", "sending");
        row(c, m, "pending");
        assertThat(sendOneAndCaptureHtml(c, m)).doesNotContain(LINE);
    }

    @Test
    void severalImports_nameEveryPlatformOnce_oldestFirst() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        Instant now = Instant.now();
        imported(orgId, m, "Dice", true, now.minusSeconds(3600));
        imported(orgId, m, "Shotgun", true, now);
        imported(orgId, m, "Dice", true, now.plusSeconds(1));
        Campaign c = campaign(orgId, "email", "sending");
        row(c, m, "pending");
        assertThat(sendOneAndCaptureHtml(c, m)).contains(LINE + "Dice, Shotgun.");
    }

    @Test
    void anEarlierBouncedEmail_orAnEarlierSms_isNotAReceivedEmail() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        imported(orgId, m, "Shotgun", true, Instant.now());
        row(campaign(orgId, "email", "sent"), m, "bounced");
        row(campaign(orgId, "sms", "sent"), m, "delivered");

        Campaign c = campaign(orgId, "email", "sending");
        row(c, m, "pending");
        assertThat(sendOneAndCaptureHtml(c, m)).contains(LINE + "Shotgun.");
    }

    @Test
    void anEarlierEmailOfAnotherOrg_doesNotCount() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        imported(orgId, m, "Shotgun", true, Instant.now());
        row(campaign(UUID.randomUUID(), "email", "sent"), m, "delivered");

        Campaign c = campaign(orgId, "email", "sending");
        row(c, m, "pending");
        assertThat(sendOneAndCaptureHtml(c, m)).contains(LINE + "Shotgun.");
    }

    @Test
    void anAcceptedRowFromAnotherOrgsImport_doesNotMakeTheMemberImportedHere() {
        UUID orgId = UUID.randomUUID();
        Member m = member(orgId);
        imported(UUID.randomUUID(), m, "Shotgun", true, Instant.now());
        Campaign c = campaign(orgId, "email", "sending");
        row(c, m, "pending");
        assertThat(sendOneAndCaptureHtml(c, m)).doesNotContain(LINE);
    }
}
