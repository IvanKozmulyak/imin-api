package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.webhook.SvixSignatureVerifier;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SvixSignatureVerifierTest {

    // secret is "whsec_" + base64("supersecretkey0")
    private static final String SECRET =
        "whsec_" + Base64.getEncoder().encodeToString("supersecretkey0".getBytes(StandardCharsets.UTF_8));

    private static String sign(String id, String ts, String body) throws Exception {
        String toSign = id + "." + ts + "." + body;
        byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] digest = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        SvixSignatureVerifier v = new SvixSignatureVerifier();
        String id = "msg_2abc", ts = String.valueOf(System.currentTimeMillis() / 1000L), body = "{\"type\":\"email.delivered\"}";
        String header = "v1," + sign(id, ts, body);
        assertThat(v.verify(SECRET, id, ts, body, header, 300)).isTrue();
    }

    @Test
    void acceptsWhenAnyOfMultipleSignaturesMatch() throws Exception {
        SvixSignatureVerifier v = new SvixSignatureVerifier();
        String id = "msg_2abc", ts = String.valueOf(System.currentTimeMillis() / 1000L), body = "{}";
        String header = "v1,ZZZbogus v1," + sign(id, ts, body);
        assertThat(v.verify(SECRET, id, ts, body, header, 300)).isTrue();
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        SvixSignatureVerifier v = new SvixSignatureVerifier();
        String id = "msg_2abc", ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String header = "v1," + sign(id, ts, "{}");
        assertThat(v.verify(SECRET, id, ts, "{\"tampered\":true}", header, 300)).isFalse();
    }

    @Test
    void rejectsStaleTimestampOutsideTolerance() throws Exception {
        SvixSignatureVerifier v = new SvixSignatureVerifier();
        String id = "msg_2abc", body = "{}";
        String ts = String.valueOf((System.currentTimeMillis() / 1000L) - 10_000L); // 10k s old
        String header = "v1," + sign(id, ts, body);
        assertThat(v.verify(SECRET, id, ts, body, header, 300)).isFalse();
    }

    @Test
    void rejectsMissingHeaders() {
        SvixSignatureVerifier v = new SvixSignatureVerifier();
        assertThat(v.verify(SECRET, null, "123", "{}", "v1,x", 300)).isFalse();
        assertThat(v.verify(SECRET, "id", "123", "{}", null, 300)).isFalse();
    }
}
