package com.imin.iminapi.predictor.controller;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.service.CalibrationViewService;
import com.imin.iminapi.security.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * GET /api/v1/internal/predictor/calibration — founders-only (task 86cav476q).
 *
 * <p>Access model: a STATIC bearer secret ({@code imin.predictor.internal-token} /
 * {@code PREDICTOR_INTERNAL_TOKEN}), deliberately outside the organizer JWT world because the
 * page aggregates CROSS-ORG ledger data no organizer principal may see. Blank secret (the
 * default) keeps the endpoint completely dark: every request 404s, so the path is
 * indistinguishable from not existing. A wrong token also 404s — no 401/403 oracle.
 * Token comparison is constant-time ({@link MessageDigest#isEqual}).
 *
 * <p>The path is permitAll in SecurityConfig precisely so the JWT filter chain never mints an
 * organizer-scoped view of it; the token check here is the ONLY gate.
 */
@RestController
public class InternalCalibrationController {

    private final CalibrationViewService view;
    private final PredictorProperties props;

    public InternalCalibrationController(CalibrationViewService view, PredictorProperties props) {
        this.view = view;
        this.props = props;
    }

    @GetMapping(value = "/api/v1/internal/predictor/calibration", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> calibration(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String secret = props.getInternalToken();
        if (secret == null || secret.isBlank()) {
            throw ApiException.notFound("Resource"); // endpoint dark until a token is configured
        }
        String presented = bearer(authorization);
        if (presented == null || !constantTimeEquals(secret, presented)) {
            throw ApiException.notFound("Resource"); // wrong/missing token is indistinguishable
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(view.render());
    }

    private static String bearer(String authorization) {
        if (authorization == null) return null;
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authorization.substring(7).trim();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
