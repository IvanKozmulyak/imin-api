package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.ConsentConfirmationToken;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentConfirmationMailer;
import com.imin.iminapi.audience.service.ConsentConfirmationRequested;
import com.imin.iminapi.audience.service.ConsentConfirmationTokenSigner;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The after-commit sender, with mocked repositories and the recording mail provider. */
class ConsentConfirmationMailerTest {

    private static final UUID TOKEN_ID = UUID.fromString("0f0f0f0f-1111-2222-3333-444455556666");
    private static final UUID ORG = UUID.fromString("0f0f0f0f-1111-2222-3333-000000000001");
    private static final UUID MEMBER = UUID.fromString("0f0f0f0f-1111-2222-3333-000000000002");
    private static final UUID CONSUMER = UUID.fromString("0f0f0f0f-1111-2222-3333-000000000003");

    private final ConsentConfirmationTokenRepository tokens = mock(ConsentConfirmationTokenRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final ConsumerRepository consumers = mock(ConsumerRepository.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final RecordingEmailService email = new RecordingEmailService();
    private final EmailProperties props = new EmailProperties();
    private final ConsentConfirmationTokenSigner signer =
            new ConsentConfirmationTokenSigner("mailer-test-key-32-bytes-zzzzzzzzzzzzz", "fallback", false);
    private ConsentConfirmationMailer mailer;

    @BeforeEach
    void setUp() {
        props.setFromAddress("noreply@imin.test");
        props.setFromName("imin");
        props.setBuyerSiteBaseUrl("https://app.imin.test");
        mailer = new ConsentConfirmationMailer(tokens, signer, memberships, consumers, orgs, email,
                new EmailTemplateRenderer(), props, mock(PlatformTransactionManager.class));
    }

    @Test
    void sends_toTheMember_fromTheOrganizerNamedAsInTheConsent_withASingleLineSubject() {
        stubAll("Vechirka\r\nBcc: x@evil.test", "Brand Name");

        mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID));

        assertThat(email.sent()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo("guest@confirm.test");
            assertThat(m.subject()).isEqualTo("Confirmez votre e-mail pour Vechirka  Bcc: x@evil.test")
                    .doesNotContain("\r").doesNotContain("\n");
            assertThat(m.text()).contains("https://app.imin.test/consent/confirm?t=" + signer.sign(TOKEN_ID));
            // The consent sentence named org.getName(), never the brand name.
            assertThat(m.text()).doesNotContain("Brand Name");
        });
        assertThat(email.lastFrom()).isEqualTo("\"Vechirka  Bcc: x@evil.test via IMIN\" <noreply@imin.test>");
        verify(tokens, never()).deleteById(TOKEN_ID);
    }

    @Test
    void rowAlreadyDeleted_sendsNothing_andDeletesNothing() {
        when(tokens.findById(TOKEN_ID)).thenReturn(Optional.empty());

        mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID));

        assertThat(email.sent()).isEmpty();
        verify(tokens, never()).deleteById(TOKEN_ID);
    }

    @Test
    void noAddress_sendsNothing_andRemovesTheRow() {
        stubAll("Vechirka", null);
        when(consumers.findByConsumerId(CONSUMER)).thenReturn(Optional.empty());

        mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID));

        assertThat(email.sent()).isEmpty();
        verify(tokens).deleteById(TOKEN_ID);
    }

    @Test
    void noMembership_sendsNothing_andRemovesTheRow() {
        stubAll("Vechirka", null);
        when(memberships.findByIdAndOrgId(MEMBER, ORG)).thenReturn(Optional.empty());

        mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID));

        assertThat(email.sent()).isEmpty();
        verify(tokens).deleteById(TOKEN_ID);
    }

    @Test
    void noOrganizer_sendsNothing_andRemovesTheRow() {
        stubAll("Vechirka", null);
        when(orgs.findById(ORG)).thenReturn(Optional.empty());

        mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID));

        assertThat(email.sent()).isEmpty();
        verify(tokens).deleteById(TOKEN_ID);
    }

    @Test
    void sendFailure_removesTheRow() {
        stubAll("Vechirka", null);
        email.failNextSendWith(new IllegalStateException("provider down"));

        mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID));

        verify(tokens).deleteById(TOKEN_ID);
    }

    @Test
    void sendFailure_thenCleanupFailure_isSwallowed() {
        stubAll("Vechirka", null);
        email.failNextSendWith(new IllegalStateException("provider down"));
        doThrow(new IllegalStateException("db down")).when(tokens).deleteById(TOKEN_ID);

        assertThatCode(() -> mailer.onRequested(new ConsentConfirmationRequested(TOKEN_ID))).doesNotThrowAnyException();
        verify(tokens).deleteById(TOKEN_ID);
    }

    @Test
    void fromHeader_escapesBackslashAndQuote_andStripsLineBreaks() {
        assertThat(props.fromHeader("A\\B \"C\"\r\nD")).isEqualTo("\"A\\\\B \\\"C\\\"  D via IMIN\" <noreply@imin.test>");
        assertThat(props.fromHeader("\r\n")).isEqualTo("imin <noreply@imin.test>");
    }

    private void stubAll(String orgName, String brandName) {
        ConsentConfirmationToken t = new ConsentConfirmationToken();
        t.setId(TOKEN_ID);
        t.setOrgId(ORG);
        t.setMembershipId(MEMBER);
        t.setConsentRecordId(UUID.randomUUID());
        t.setLocale("fr");
        t.setSentAt(Instant.parse("2026-09-28T10:00:00Z"));
        t.setExpiresAt(Instant.parse("2026-10-05T10:00:00Z"));
        when(tokens.findById(TOKEN_ID)).thenReturn(Optional.of(t));
        Membership m = new Membership();
        m.setMembershipId(MEMBER);
        m.setOrgId(ORG);
        m.setConsumerId(CONSUMER);
        when(memberships.findByIdAndOrgId(MEMBER, ORG)).thenReturn(Optional.of(m));
        Consumer c = new Consumer();
        c.setConsumerId(CONSUMER);
        c.setNormalizedEmail("guest@confirm.test");
        when(consumers.findByConsumerId(CONSUMER)).thenReturn(Optional.of(c));
        Organization o = new Organization();
        o.setName(orgName);
        o.setBrandName(brandName);
        when(orgs.findById(ORG)).thenReturn(Optional.of(o));
    }
}
