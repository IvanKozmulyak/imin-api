package com.imin.iminapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * RestClient for the native Ideogram V3 API (generate + remix). Authenticates with the
 * {@code Api-Key} header. Fails fast with a clear message when the key is missing so a
 * misconfigured deploy surfaces a config error rather than an opaque 401.
 */
@Configuration
public class IdeogramImageConfig {

    private static final Logger log = LoggerFactory.getLogger(IdeogramImageConfig.class);

    @Value("${ideogram.api-key:${IDEOGRAM_API_KEY:}}")
    private String apiKey;

    @Value("${ideogram.base-url:https://api.ideogram.ai}")
    private String baseUrl;

    @Bean
    public RestClient ideogramRestClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("IDEOGRAM_API_KEY is not set — poster rendering will fail with 401. "
                    + "Set the environment variable and restart the app.");
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(apiKeyInterceptor(apiKey, hostOf(baseUrl)))
                .build();
    }

    /**
     * Attaches {@code Api-Key} to requests for {@code apiHost} ONLY. The same RestClient also GETs
     * the rendered image, whose URL comes out of the Ideogram response body — today that is
     * Ideogram's own CDN, but nothing in the code constrains it to any host, so an unscoped
     * interceptor would hand the secret to whatever host the upstream response named.
     */
    static ClientHttpRequestInterceptor apiKeyInterceptor(String apiKey, String apiHost) {
        return (request, body, execution) -> {
            if (apiHost.equalsIgnoreCase(request.getURI().getHost())) {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "IDEOGRAM_API_KEY is not configured. Set the environment variable and "
                            + "restart the app before generating posters.");
                }
                request.getHeaders().set("Api-Key", apiKey);
            }
            return execution.execute(request, body);
        };
    }

    /** Fails fast: a base URL with no host would make the scoping above silently authenticate nothing. */
    private static String hostOf(String baseUrl) {
        String host = baseUrl == null || baseUrl.isBlank() ? null : URI.create(baseUrl).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("ideogram.base-url must be an absolute URL with a host, got: " + baseUrl);
        }
        return host;
    }
}
