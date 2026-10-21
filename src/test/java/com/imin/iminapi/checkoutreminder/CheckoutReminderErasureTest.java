package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.dto.DsarRecords;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.service.BuyerAccountErasureService;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.util.Times;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An erased person's address leaves the reminder rows, and an access export lists them while they are held. */
@IminIntegrationTest
class CheckoutReminderErasureTest {

    @Autowired DsarService dsar;
    @Autowired BuyerAccountErasureService accountErasure;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository accountEmails;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ErasedAddressRepository erased;

    private ReminderRows rows;
    private ReminderRows.Org org;
    private ReminderRows.Org otherOrg;
    private String email;
    private UUID ours;
    private UUID theirs;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        // The other org's row is created first, so a query that ignored the org would meet it first.
        otherOrg = rows.org(now);
        org = rows.org(now);
        email = "erase-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        theirs = row(otherOrg, now);
        ours = row(org, now);
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"orgDsarErase", "addressEraseWithNoMember", "addressEraseOfAMember",
            "addressEraseOfAMemberAlreadyPending", "buyerAccountErasure", "accessExport"})
    void erasureRemovesReminderAddresses(String variant) throws Exception {
        // Most people behind a reminder never joined the audience: no consumer, no membership.
        Membership member = variant.equals("addressEraseWithNoMember") ? null : rows.member(org, email, "subscribed");
        AuthPrincipal admin = new AuthPrincipal(org.owner().getId(), org.id(), UserRole.OWNER, UUID.randomUUID());
        switch (variant) {
            case "orgDsarErase" -> {
                dsar.executeErase(org.id(), member.getMembershipId(), admin);
                assertWiped(ours);
                assertThat(rows.load(theirs).getEmailNormalized()).isEqualTo(email);
                assertThat(rows.load(theirs).getStatus().name()).isEqualTo("PENDING");
            }
            case "addressEraseWithNoMember" -> {
                assertThat(consumers.findByNormalizedEmail(email)).isEmpty();
                eraseAddress(admin).andExpect(status().isNoContent());
                assertWiped(ours);
                assertThat(rows.load(theirs).getEmailNormalized()).isEqualTo(email);
                assertThat(rows.load(theirs).getStatus().name()).isEqualTo("PENDING");
                assertThat(erased.existsForOrg(org.id(), email)).isTrue();
                assertThat(erased.existsForOrg(otherOrg.id(), email)).isFalse();
            }
            // A member's address takes the member's erase: grace period first, records wiped when it runs.
            case "addressEraseOfAMember" -> {
                eraseAddress(admin).andExpect(status().isAccepted());
                assertThat(jdbc.queryForObject("SELECT status FROM memberships WHERE membership_id = ?", String.class,
                        member.getMembershipId())).isEqualTo("erase_pending");
                assertThat(rows.load(ours).getEmailNormalized()).isEqualTo(email);
            }
            // A second request for the same person keeps the erase date the first one set.
            case "addressEraseOfAMemberAlreadyPending" -> {
                Timestamp eraseAt = Timestamp.from(Instant.now().plus(Duration.ofDays(12)).truncatedTo(ChronoUnit.MICROS));
                jdbc.update("UPDATE memberships SET status = 'erase_pending', erase_at = ? WHERE membership_id = ?",
                        eraseAt, member.getMembershipId());
                eraseAddress(admin).andExpect(status().isAccepted());
                assertThat(jdbc.queryForObject("SELECT erase_at FROM memberships WHERE membership_id = ?",
                        Timestamp.class, member.getMembershipId())).isEqualTo(eraseAt);
            }
            case "buyerAccountErasure" -> {
                BuyerAccount a = new BuyerAccount();
                a.setActivatedAt(Times.nowMicros());
                UUID accountId = accounts.save(a).getId();
                BuyerAccountEmail address = BuyerAccountEmail.of(accountId, email, BuyerAccountEmail.ADDED_VIA_SIGNUP);
                address.markVerified(Times.nowMicros());
                accountEmails.save(address);
                accountErasure.erase(accountId);
                assertWiped(ours);
                assertWiped(theirs);
            }
            case "accessExport" -> {
                DsarRecords records = dsar.exportRecords(org.id(), member.getMembershipId(), admin);
                assertThat(records.checkoutReminders()).singleElement().satisfies(r -> {
                    assertThat(r.id()).isEqualTo(ours);
                    assertThat(r.email()).isEqualTo(email);
                    assertThat(r.consentProof()).isEqualTo(ReminderRows.LABEL.formatted(org.org().getName()));
                    assertThat(r.consentTextVersion()).isEqualTo(ReminderRows.VERSION);
                    assertThat(r.status()).isEqualTo("PENDING");
                });
            }
            default -> throw new IllegalArgumentException(variant);
        }
    }

    /** The address in another case than stored, as an organizer would type it from a request. */
    private ResultActions eraseAddress(AuthPrincipal p) throws Exception {
        return mvc.perform(post("/api/v1/audience/erase-address")
                .with(authentication(new UsernamePasswordAuthenticationToken(p, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())))))
                .contentType("application/json")
                .content("{\"email\":\"" + email.toUpperCase() + "\"}"));
    }

    private UUID row(ReminderRows.Org o, Instant now) {
        Event night = rows.night(o, now.plus(Duration.ofDays(5)));
        TicketTier tier = rows.tier(night, 1600, 50);
        return rows.waiting(o, night, tier, email, 1, "en", false, now.minus(Duration.ofMinutes(10)),
                now.plus(Duration.ofMinutes(50)));
    }

    private void assertWiped(UUID id) {
        CheckoutReminder r = rows.load(id);
        assertThat(r.getEmailNormalized()).isNull();
        assertThat(r.getConsentProof()).isNull();
        assertThat(r.getAddressWipedAt()).isNotNull();
        assertThat(r.getStatus().name()).isEqualTo("NOT_SENT");
        assertThat(r.getReason().name()).isEqualTo("ERASED");
    }
}
