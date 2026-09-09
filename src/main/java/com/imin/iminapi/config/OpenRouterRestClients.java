package com.imin.iminapi.config;

import org.springframework.web.client.RestClient;

/**
 * The one place a raw OpenRouter {@code RestClient} is built.
 *
 * <p>{@code OpenRouterPosterTextValidationClient}, {@code OpenRouterPosterStyleValidationClient}
 * and the {@code StyleCardGenerator} each carried a verbatim copy of the base-url normalization
 * and its own hand-rolled bearer interceptor. That is exactly the duplication that let the style
 * client drift: the data-collection opt-out had to be added to each of them separately. A
 * cross-cutting change to the OpenRouter transport now lands once, here.
 *
 * <p>The request BODY still carries {@link OpenRouterPrivacy#providerPolicy()} per call — OpenRouter
 * has no header form for it, so it cannot live in the transport.
 */
public final class OpenRouterRestClients {

    private OpenRouterRestClients() {}

    /**
     * A bearer-authenticated client rooted at OpenRouter's {@code /v1}.
     *
     * @param purpose named in the error thrown when the key is missing, so a misconfigured deploy
     *                says which feature it just disabled
     */
    public static RestClient v1(String baseUrl, String apiKey, String purpose) {
        String normalized = v1BaseUrl(baseUrl);
        return RestClient.builder()
                .baseUrl(normalized)
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "OPENROUTER_API_KEY is not configured. Set it before " + purpose + ".");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Trailing slashes trimmed, {@code /v1} appended unless already present. */
    public static String v1BaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalStateException("openrouter.base-url is not configured");
        }
        if (normalized.endsWith("/v1")) {
            return normalized;
        }
        return normalized + "/v1";
    }
}
