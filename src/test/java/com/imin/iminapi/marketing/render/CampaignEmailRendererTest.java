package com.imin.iminapi.marketing.render;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignEmailRendererTest {

    final CampaignEmailRenderer renderer =
            new CampaignEmailRenderer(new UtmLinkRewriter());

    @Test
    void rendersMarkdownAndEscapesRawHtml() {
        String md = "Hello **world** <script>alert('x')</script>";
        CampaignEmailRenderer.Rendered r = renderer.render(
                "Subject", "Preheader", md, "camp-1", "email",
                "https://app.imin.wtf/optout?token=TKN");
        assertThat(r.html()).contains("<strong>world</strong>");
        // raw HTML must be escaped, not passed through
        assertThat(r.html()).doesNotContain("<script>");
        assertThat(r.html()).contains("&lt;script&gt;");
    }

    @Test
    void appendsUnsubscribeFooterWithTokenLink() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "body", "camp-1", "email",
                "https://app.imin.wtf/optout?token=TKN");
        assertThat(r.html()).contains("https://app.imin.wtf/optout?token=TKN");
        assertThat(r.html().toLowerCase()).contains("unsubscribe");
    }

    @Test
    void rewritesIminLinksWithUtm() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "[Buy](https://imin.wtf/e/x)", "camp-9", "email",
                "https://app.imin.wtf/optout?token=TKN");
        assertThat(r.html()).contains("utm_campaign=camp-9");
    }

    @Test
    void refusesToRenderWithoutUnsubscribeUrl() {
        assertThatThrownBy(() -> renderer.render(
                "S", "P", "body", "camp-1", "email", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsubscribe");
    }

    @Test
    void producesPlainTextVariantWithFooter() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Hello world", "camp-1", "email",
                "https://app.imin.wtf/optout?token=TKN");
        assertThat(r.text()).contains("Hello world");
        assertThat(r.text()).contains("https://app.imin.wtf/optout?token=TKN");
    }
}
