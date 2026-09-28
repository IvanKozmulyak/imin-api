package com.imin.iminapi.email;

import com.resend.Resend;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceHeadersTest {

    /** An implementation that only knows the four-argument send. */
    static final class PlainOnly implements EmailService {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(String to, String subject, String html, String text) {
            sent.add(to);
        }
    }

    @Test
    void default_withNoHeaders_delegatesToThePlainSend() {
        PlainOnly svc = new PlainOnly();

        svc.send("a@x", "s", "h", "t", Map.of());
        svc.send("b@x", "s", "h", "t", null);

        assertThat(svc.sent).containsExactly("a@x", "b@x");
    }

    @Test
    void default_refusesToDropHeaders() {
        PlainOnly svc = new PlainOnly();

        assertThatThrownBy(() -> svc.send("a@x", "s", "h", "t", Map.of("AI-Disclosure", "mode=ai-originated")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(svc.sent).isEmpty();
    }

    private static ResendEmailService resendWith(Emails emails) {
        Resend resend = mock(Resend.class);
        when(resend.emails()).thenReturn(emails);
        EmailProperties props = new EmailProperties();
        props.setApiKey("re_test");
        props.setFromAddress("noreply@imin.test");
        return new ResendEmailService(resend, props);
    }

    @Test
    void resend_passesTheHeadersThrough() throws Exception {
        Emails emails = mock(Emails.class);
        ResendEmailService svc = resendWith(emails);

        svc.send("a@x", "s", "<p>h</p>", "t", Map.of("AI-Disclosure", "mode=ai-originated"));

        ArgumentCaptor<CreateEmailOptions> opts = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(opts.capture());
        assertThat(opts.getValue().getHeaders()).containsEntry("AI-Disclosure", "mode=ai-originated");
    }

    @Test
    void resend_plainSend_setsNoHeaders() throws Exception {
        Emails emails = mock(Emails.class);
        ResendEmailService svc = resendWith(emails);

        svc.send("a@x", "s", "<p>h</p>", "t");

        ArgumentCaptor<CreateEmailOptions> opts = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(opts.capture());
        assertThat(opts.getValue().getHeaders()).isNullOrEmpty();
    }

    @Test
    void default_refusesToDropAFromOverride() {
        PlainOnly svc = new PlainOnly();

        assertThatThrownBy(() -> svc.sendFrom("\"Org via IMIN\" <a@imin.test>", "a@x", "s", "h", "t"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(svc.sent).isEmpty();
    }

    @Test
    void resend_sendFrom_usesTheGivenFrom() throws Exception {
        Emails emails = mock(Emails.class);
        ResendEmailService svc = resendWith(emails);

        svc.sendFrom("\"Vechirka via IMIN\" <noreply@imin.test>", "a@x", "s", "<p>h</p>", "t");

        ArgumentCaptor<CreateEmailOptions> opts = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(opts.capture());
        assertThat(opts.getValue().getFrom()).isEqualTo("\"Vechirka via IMIN\" <noreply@imin.test>");
        assertThat(opts.getValue().getTo()).containsExactly("a@x");
    }

    @Test
    void resend_plainSend_usesTheConfiguredFrom() throws Exception {
        Emails emails = mock(Emails.class);
        ResendEmailService svc = resendWith(emails);

        svc.send("a@x", "s", "<p>h</p>", "t");

        ArgumentCaptor<CreateEmailOptions> opts = ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(opts.capture());
        assertThat(opts.getValue().getFrom()).isEqualTo("noreply@imin.test");
    }

    @Test
    void fromHeader_forAnOrganizer_isQuotedViaImin_andBlankFallsBack() {
        EmailProperties props = new EmailProperties();
        props.setFromAddress("noreply@imin.test");
        props.setFromName("imin");

        assertThat(props.fromHeader("Vechirka \"Night\"")).isEqualTo("\"Vechirka \\\"Night\\\" via IMIN\" <noreply@imin.test>");
        assertThat(props.fromHeader("  ")).isEqualTo("imin <noreply@imin.test>");
        assertThat(props.fromHeader(null)).isEqualTo("imin <noreply@imin.test>");
        props.setFromAddress("");
        assertThat(props.fromHeader("Vechirka")).isEqualTo("imin <>");
    }
}
