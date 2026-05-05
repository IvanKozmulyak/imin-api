package com.imin.iminapi.email;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResendEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private final Resend resend;
    private final EmailProperties props;

    public ResendEmailService(Resend resend, EmailProperties props) {
        this.resend = resend;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String html, String text) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.error("RESEND_API_KEY not configured; cannot send email to {}", to);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                    "Email service not configured");
        }
        if (props.getFromAddress() == null || props.getFromAddress().isBlank()) {
            log.error("IMIN_EMAIL_FROM_ADDRESS not configured; cannot send email to {}", to);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                    "Email service not configured");
        }
        CreateEmailOptions.Builder b = CreateEmailOptions.builder()
                .from(props.fromHeader())
                .to(to)
                .subject(subject)
                .html(html)
                .text(text);
        if (props.getReplyTo() != null && !props.getReplyTo().isBlank()) {
            b.replyTo(props.getReplyTo());
        }
        try {
            resend.emails().send(b.build());
        } catch (ResendException e) {
            log.error("Resend API call failed for {}: {}", to, e.getMessage(), e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Email service unavailable", e);
        }
    }
}
