package com.imin.iminapi.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends push notifications through Expo's push service, which fans out to both
 * APNs and FCM from one HTTP call with one credential.
 *
 * <p><b>Why Expo rather than Firebase Admin + APNs directly:</b> the client is
 * an Expo app, so its tokens are already Expo push tokens, and this avoids a
 * Firebase service account, an APNs {@code .p8} key, JWT minting, and a second
 * SDK — for a feature whose entire v1 scope is one notification type.
 *
 * <p><b>The ceiling, and the upgrade path:</b> this couples delivery to Expo's
 * availability and to their 100-messages-per-request limit. If volume or
 * independence ever demands it, replace the body of {@link #send} with Firebase
 * Admin plus an APNs client; {@link PushMessage} and the call site in
 * {@code NotifyReleaseSender} do not change.
 *
 * <p><b>Failure policy:</b> never throws. Push is an enhancement to an email
 * that is already being sent, and a push outage must not stop or duplicate that
 * email. Tokens Expo reports as {@code DeviceNotRegistered} are returned so the
 * caller can revoke them — that is the only way the registry stays clean, since
 * a deleted app never tells us it is gone.
 *
 * <p>The {@link RestClient} is injected rather than built here, so the timeouts
 * live in one reviewable place ({@code PushConfig}) and a test can bind a
 * {@code MockRestServiceServer} to the builder without the constructor
 * overwriting its request factory.
 */
@Component
public class ExpoPushSender {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);

    /** Expo's documented per-request maximum. */
    static final int MAX_BATCH = 100;

    /**
     * The response is read as text and parsed here rather than handed to a
     * message converter. Boot 4 ships <b>both</b> Jackson generations and wires
     * its HTTP converters to Jackson 3, so asking {@code retrieve()} for a
     * Jackson-2 {@code JsonNode} fails at runtime with a type-definition error
     * — not at compile time. Parsing explicitly, the way
     * {@code ResendWebhookController} and {@code NominatimGeocoder} do, keeps
     * this client independent of which converter happens to be registered.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PushProperties props;
    private final RestClient http;

    public ExpoPushSender(PushProperties props, @Qualifier("expoRestClient") RestClient http) {
        this.props = props;
        this.http = http;
    }

    /** What one fan-out achieved, plus the tokens that must be revoked. */
    public record Result(int accepted, Set<String> deadTokens) {
        public static final Result NONE = new Result(0, Set.of());
    }

    /**
     * Deliver, best effort.
     *
     * <p>When the feature is disabled this returns before any HTTP work is
     * prepared, let alone performed — dark means no call, not a quiet one.
     */
    public Result send(List<PushMessage> messages) {
        if (!props.isEnabled() || messages == null || messages.isEmpty()) return Result.NONE;

        int accepted = 0;
        Set<String> dead = new LinkedHashSet<>();
        for (List<PushMessage> chunk : batch(messages)) {
            try {
                JsonNode response = post(chunk);
                accepted += readTickets(response, chunk, dead);
            } catch (Exception e) {
                // At-most-once for push, deliberately. The email is the promise;
                // this is the extra. Retrying risks double-notifying somebody.
                log.warn("[push] batch of {} failed — {}", chunk.size(), e.getMessage());
            }
        }
        log.info("[push] accepted={} dead={} of {}", accepted, dead.size(), messages.size());
        return new Result(accepted, dead);
    }

    /** Split into Expo-sized chunks. Package-private so the boundary is testable. */
    static List<List<PushMessage>> batch(List<PushMessage> messages) {
        List<List<PushMessage>> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); i += MAX_BATCH) {
            out.add(List.copyOf(messages.subList(i, Math.min(i + MAX_BATCH, messages.size()))));
        }
        return out;
    }

    private JsonNode post(List<PushMessage> chunk) throws Exception {
        List<Map<String, Object>> body = chunk.stream().map(m -> {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("to", m.to());
            msg.put("title", m.title());
            msg.put("body", m.body());
            msg.put("sound", "default");
            // Android requires a channel the app created at startup; without a
            // matching one the notification is delivered silently on Android 8+.
            msg.put("channelId", m.channelId());
            if (m.data() != null && !m.data().isEmpty()) msg.put("data", m.data());
            return msg;
        }).toList();

        RestClient.RequestBodySpec spec = http.post()
                .uri(props.getBaseUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (props.getAccessToken() != null && !props.getAccessToken().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + props.getAccessToken());
        }
        String raw = spec.body(body).retrieve().body(String.class);
        return raw == null || raw.isBlank() ? null : MAPPER.readTree(raw);
    }

    /**
     * Expo answers with one ticket per message, in order. A ticket with
     * {@code details.error = DeviceNotRegistered} means the app was uninstalled
     * or the token rotated; that token is dead and must never be sent to again.
     *
     * <p>Every other error is <b>transient</b> and must not revoke anything.
     * {@code MessageRateExceeded} in particular describes a device that is very
     * much alive and is simply being throttled; treating it as dead would
     * silently unsubscribe the busiest users.
     */
    private int readTickets(JsonNode response, List<PushMessage> chunk, Set<String> dead) {
        if (response == null) return 0;
        JsonNode tickets = response.path("data");
        if (!tickets.isArray()) return 0;
        int ok = 0;
        for (int i = 0; i < tickets.size() && i < chunk.size(); i++) {
            JsonNode ticket = tickets.get(i);
            if ("ok".equals(ticket.path("status").asText())) {
                ok++;
                continue;
            }
            String error = ticket.path("details").path("error").asText("");
            if ("DeviceNotRegistered".equals(error)) {
                dead.add(chunk.get(i).to());
            } else {
                log.warn("[push] ticket error {} — {}", error, ticket.path("message").asText(""));
            }
        }
        return ok;
    }
}
