package com.imin.iminapi.buyer.security;

import com.imin.iminapi.buyer.BuyerProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Direct tests of the CSRF mitigations, with no CORS filter in front of them —
 * in the running application a foreign origin is usually stopped by CORS first,
 * which would hide whether this filter does its job. The Origin check exists
 * precisely so the protection does not depend on CORS or on {@code SameSite}
 * (epic §2.6).
 */
class BuyerRequestGuardFilterTest {

    BuyerProperties props = new BuyerProperties();
    BuyerRequestGuardFilter filter;

    @BeforeEach
    void setup() {
        filter = new BuyerRequestGuardFilter(props);
    }

    private static MockHttpServletRequest post(String uri) {
        var req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        return req;
    }

    private static MockHttpServletRequest json(String uri, String origin) {
        var req = post(uri);
        if (origin != null) req.addHeader("Origin", origin);
        req.setContentType("application/json");
        req.setContent("{}".getBytes());
        return req;
    }

    @Test
    void allowed_origin_with_json_passes() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = json("/api/v1/buyer/sessions/revoke-all", "http://localhost:3000");
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void foreign_origin_is_rejected_with_403() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var resp = new MockHttpServletResponse();
        filter.doFilter(json("/api/v1/buyer/sessions/revoke-all", "https://evil.example"), resp, chain);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    void a_wildcard_vercel_origin_is_not_an_allowed_buyer_origin() throws Exception {
        var resp = new MockHttpServletResponse();
        filter.doFilter(json("/api/v1/buyer/me", "https://imin-public-attacker.vercel.app"),
                resp, mock(FilterChain.class));
        assertThat(resp.getStatus()).isEqualTo(403);
    }

    @Test
    void missing_origin_is_rejected() throws Exception {
        var resp = new MockHttpServletResponse();
        filter.doFilter(json("/api/v1/buyer/auth/logout", null), resp, mock(FilterChain.class));
        assertThat(resp.getStatus()).isEqualTo(403);
    }

    @Test
    void form_encoded_body_is_rejected_with_415() throws Exception {
        var req = post("/api/v1/buyer/auth/logout");
        req.addHeader("Origin", "http://localhost:3000");
        req.setContentType("application/x-www-form-urlencoded");
        req.setContent("a=b".getBytes());
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, mock(FilterChain.class));
        assertThat(resp.getStatus()).isEqualTo(415);
        assertThat(resp.getContentAsString()).contains("INVALID_REQUEST");
    }

    @Test
    void multipart_body_is_rejected_with_415() throws Exception {
        var req = post("/api/v1/buyer/auth/logout");
        req.addHeader("Origin", "http://localhost:3000");
        req.setContentType("multipart/form-data; boundary=x");
        req.setContent("--x--".getBytes());
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, mock(FilterChain.class));
        assertThat(resp.getStatus()).isEqualTo(415);
    }

    @Test
    void text_plain_body_is_rejected_too() throws Exception {
        // fetch() with a string body and no explicit header sends text/plain —
        // the third CORS-"simple" content type, and therefore preflight-free.
        var req = post("/api/v1/buyer/auth/logout");
        req.addHeader("Origin", "http://localhost:3000");
        req.setContentType("text/plain;charset=UTF-8");
        req.setContent("{}".getBytes());
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, mock(FilterChain.class));
        assertThat(resp.getStatus()).isEqualTo(415);
    }

    @Test
    void bodyless_mutation_may_omit_the_content_type() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = post("/api/v1/buyer/auth/logout");
        req.addHeader("Origin", "http://localhost:3000");
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void reads_are_untouched() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = new MockHttpServletRequest("GET", "/api/v1/buyer/me");
        req.setRequestURI("/api/v1/buyer/me");
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void non_buyer_paths_are_untouched() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = post("/api/v1/auth/login");
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }
}
