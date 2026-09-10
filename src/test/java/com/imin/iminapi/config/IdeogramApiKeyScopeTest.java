package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * poster-10: the Ideogram RestClient POSTs to the API and then GETs the rendered image at a URL
 * taken from the API's own response body. The interceptor runs on both, so an unscoped one hands
 * the IDEOGRAM_API_KEY to whatever host the upstream response names.
 */
class IdeogramApiKeyScopeTest {

    private static MockClientHttpRequest sendTo(String url) throws Exception {
        ClientHttpRequestInterceptor interceptor =
                IdeogramImageConfig.apiKeyInterceptor("secret-key", "api.ideogram.ai");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create(url));
        interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        return request;
    }

    @Test
    void apiKeyIsSentToTheIdeogramApiHost() throws Exception {
        assertThat(sendTo("https://api.ideogram.ai/v1/ideogram-v3/generate")
                .getHeaders().getFirst("Api-Key")).isEqualTo("secret-key");
    }

    @Test
    void apiKeyIsNotSentToTheImageUrlFromTheResponseBody() throws Exception {
        assertThat(sendTo("https://cdn.ideogram.ai/x.png").getHeaders().getFirst("Api-Key")).isNull();
        assertThat(sendTo("https://attacker.example/x.png").getHeaders().getFirst("Api-Key")).isNull();
    }
}
