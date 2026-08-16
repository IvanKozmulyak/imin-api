package com.imin.iminapi.marketing.email;

import com.imin.iminapi.marketing.unsubscribe.PublicUnsubscribeController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unsubscribe URL must resolve to a handler that exists.
 *
 * <p>It did not, from the Momentum Engine going live until 2026-08-16: the
 * footer link and the {@code List-Unsubscribe} header both carried
 * {@code https://app.imin.wtf/optout?token=…}, and {@code imin-public} has
 * never served {@code /optout}. Confirmed against production — it answered 404.
 *
 * <p>The reason it survived review is that every existing assertion checked the
 * <i>shape</i> of the string. A URL can be perfectly well-formed, carry a valid
 * signed token, and still point at nothing. So this test does not assert a
 * literal: it derives the path the controller actually publishes from the
 * controller's own annotations, and requires the emitted URL to match it. Move
 * the endpoint and this fails; hand-edit the builder and this fails.
 */
class UnsubscribeUrlTest {

    private static MarketingEmailProperties props() {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setApiPublicBaseUrl("https://api.imin.wtf");
        p.setBuyerSiteBaseUrl("https://app.imin.wtf");
        return p;
    }

    /** The path is read off the controller, not copied from it. */
    private static String publishedPath() {
        RequestMapping cls = PublicUnsubscribeController.class.getAnnotation(RequestMapping.class);
        assertThat(cls).as("the controller's class-level @RequestMapping").isNotNull();
        return cls.value()[0];
    }

    @Test
    void theEmittedUrlHitsThePathTheControllerPublishes() {
        String url = props().unsubscribeUrl("tok_abc123");
        assertThat(URI.create(url).getPath())
                .as("an unsubscribe link that does not resolve is a dead opt-out")
                .isEqualTo(publishedPath() + "/tok_abc123");
    }

    @Test
    void theTokenIsAPathSegmentNotAQueryParameter() {
        // The old form was ?token=…; the controller binds @PathVariable, so a
        // query parameter reaches no handler at all.
        String url = props().unsubscribeUrl("tok_abc123");
        assertThat(URI.create(url).getQuery()).isNull();
        assertThat(url).doesNotContain("/optout");
    }

    @Test
    void oneUrlServesBothVerbs() throws Exception {
        // RFC 8058 one-click POSTs to the List-Unsubscribe-Post URL, and a human
        // clicking the footer GETs the same one. If either mapping disappears,
        // half the recipients lose their opt-out silently.
        assertThat(PublicUnsubscribeController.class.getMethod("oneClick", String.class)
                .getAnnotation(PostMapping.class)).isNotNull();
        assertThat(PublicUnsubscribeController.class.getMethod("confirmPage", String.class)
                .getAnnotation(GetMapping.class)).isNotNull();
    }

    @Test
    void itIsBuiltFromTheApiHostNotTheBuyerSite() {
        // The opt-out is served by the API. Pointing it at the buyer site is the
        // original defect, and the two hosts differ in production.
        assertThat(props().unsubscribeUrl("t")).startsWith("https://api.imin.wtf/");
    }

    /**
     * Asserted on the RAW path: {@code getPath()} decodes, so it would report a
     * smuggled {@code /} as a legitimate segment boundary and pass.
     */
    @Test
    void aTokenWithReservedCharactersStaysOneSegment() {
        String raw = URI.create(props().unsubscribeUrl("a/b c")).getRawPath();
        assertThat(raw)
                .as("a token containing / must not become two path segments")
                .isEqualTo(publishedPath() + "/a%2Fb%20c");
        // %20, not +. URLEncoder's + is a space only in a query string; in a path
        // segment it is a literal plus, and the token would no longer verify.
        assertThat(raw).doesNotContain("+");
    }
}
