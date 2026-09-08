package com.imin.iminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The provider opt-out sent on every OpenRouter request.
 *
 * <p>OpenRouter routes a request to whichever upstream provider is cheapest or
 * fastest, and some of those providers train on what they receive by default.
 * What imin sends them is not innocuous: {@code OpenRouterPosterTextValidationClient}
 * base64-uploads the finished poster to the vision gate, and when the poster was
 * rendered from a DJ character reference that image contains a real person's
 * likeness. {@code "provider": {"data_collection": "deny"}} restricts routing to
 * providers that do not store or train on request data.
 *
 * <p>It is a <b>body</b> field, not a header — OpenRouter has no header form —
 * which is why the Spring AI {@code ChatClient} path needs
 * {@link #bodyInjectingInterceptor()}: {@code OpenAiChatOptions} models the
 * OpenAI schema and has nowhere to put a vendor extension. The raw
 * {@code RestClient} callers build their own body maps and use
 * {@link #providerPolicy()} directly.
 */
public final class OpenRouterPrivacy {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterPrivacy.class);

    /** OpenRouter's request-body key for routing policy. */
    public static final String PROVIDER_FIELD = "provider";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenRouterPrivacy() {}

    /**
     * A fresh, mutable copy each call — these maps are handed to request bodies
     * that other code may add to, and a shared constant map would be a
     * cross-request mutation waiting to happen.
     */
    public static Map<String, Object> providerPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("data_collection", "deny");
        return policy;
    }

    /**
     * Adds the policy to an outgoing JSON body, for clients that cannot express
     * a vendor field in their options object (Spring AI's OpenAI model).
     *
     * <p>Fails open on anything unexpected — a body that is not a JSON object, or
     * that already carries a {@code provider} field the caller set deliberately,
     * is passed through untouched. Corrupting a request to enforce a privacy flag
     * would take the whole feature down; the failure is logged instead.
     */
    public static ClientHttpRequestInterceptor bodyInjectingInterceptor() {
        return (request, body, execution) -> {
            byte[] outgoing = body;
            try {
                if (body != null && body.length > 0) {
                    var node = MAPPER.readTree(body);
                    if (node instanceof ObjectNode obj && !obj.has(PROVIDER_FIELD)) {
                        obj.set(PROVIDER_FIELD, MAPPER.valueToTree(providerPolicy()));
                        outgoing = MAPPER.writeValueAsBytes(obj);
                        request.getHeaders().setContentLength(outgoing.length);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not add the OpenRouter data-collection opt-out to this request: {}",
                        e.getMessage());
                outgoing = body;
            }
            return execution.execute(request, outgoing);
        };
    }

    /** Visible for testing: the injection applied to a raw JSON body. */
    static byte[] withProviderPolicy(byte[] jsonBody) throws Exception {
        var node = MAPPER.readTree(jsonBody);
        if (node instanceof ObjectNode obj && !obj.has(PROVIDER_FIELD)) {
            obj.set(PROVIDER_FIELD, MAPPER.valueToTree(providerPolicy()));
            return MAPPER.writeValueAsBytes(obj);
        }
        return jsonBody;
    }
}
