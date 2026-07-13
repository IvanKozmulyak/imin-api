package com.imin.iminapi.marketing.render;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UtmLinkRewriterTest {

    final UtmLinkRewriter rewriter = new UtmLinkRewriter();

    @Test
    void appendsUtmParamsToIminWtfLink() {
        String html = "<a href=\"https://imin.wtf/e/summer-fest\">Buy tickets</a>";
        String out = rewriter.rewrite(html, "email", "camp-123");
        assertThat(out).contains("utm_source=imin");
        assertThat(out).contains("utm_medium=email");
        assertThat(out).contains("utm_campaign=camp-123");
        assertThat(out).contains("https://imin.wtf/e/summer-fest?");
    }

    @Test
    void mergesIntoExistingQueryStringWithAmpersand() {
        String html = "<a href=\"https://imin.wtf/e/x?ref=news\">go</a>";
        String out = rewriter.rewrite(html, "email", "c1");
        assertThat(out).contains("ref=news&utm_source=imin");
    }

    @Test
    void leavesNonIminLinksUntouched() {
        String html = "<a href=\"https://example.com/foo\">ext</a>";
        String out = rewriter.rewrite(html, "email", "c1");
        assertThat(out).isEqualTo(html);
    }

    @Test
    void doesNotDoubleTagAlreadyTaggedLink() {
        String html = "<a href=\"https://imin.wtf/e/x?utm_source=imin\">go</a>";
        String out = rewriter.rewrite(html, "email", "c1");
        assertThat(out).isEqualTo(html);
    }
}
