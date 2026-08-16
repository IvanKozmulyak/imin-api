package com.imin.iminapi.marketing.render;

import com.imin.iminapi.marketing.template.BuiltinTemplates;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignEmailRendererTest {

    final CampaignEmailRenderer renderer =
            new CampaignEmailRenderer(new UtmLinkRewriter());

    /**
     * The shape production actually emits — {@link
     * com.imin.iminapi.marketing.email.MarketingEmailProperties#unsubscribeUrl}
     * builds {@code <api host>/api/v1/public/unsubscribe/<token>}.
     *
     * <p>Every fixture here pinned {@code https://app.imin.wtf/optout?token=TKN}
     * until 2026-08-16 — a URL the code can no longer produce and which 404'd
     * for the whole time it could. The tests passed throughout, because the
     * renderer treats this argument as an opaque string; that is exactly why a
     * stale fixture here is worth correcting rather than leaving green. Only
     * {@code UnsubscribeUrlTest} proves the URL resolves; this file must at
     * least not contradict it.
     */
    private static final String UNSUB =
            "https://api.imin.wtf/api/v1/public/unsubscribe/TKN";

    @Test
    void rendersMarkdownAndEscapesRawHtml() {
        String md = "Hello **world** <script>alert('x')</script>";
        CampaignEmailRenderer.Rendered r = renderer.render(
                "Subject", "Preheader", md, "camp-1", "email",
                UNSUB);
        assertThat(r.html()).contains("<strong>world</strong>");
        // raw HTML must be escaped, not passed through
        assertThat(r.html()).doesNotContain("<script>");
        assertThat(r.html()).contains("&lt;script&gt;");
    }

    @Test
    void appendsUnsubscribeFooterWithTokenLink() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "body", "camp-1", "email",
                UNSUB);
        assertThat(r.html()).contains(UNSUB);
        assertThat(r.html().toLowerCase()).contains("unsubscribe");
    }

    @Test
    void rewritesIminLinksWithUtm() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "[Buy](https://imin.wtf/e/x)", "camp-9", "email",
                UNSUB);
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
                UNSUB);
        assertThat(r.text()).contains("Hello world");
        assertThat(r.text()).contains(UNSUB);
    }

    // ---- template-driven rendering (V66) ----

    @Test
    void appliesTemplatePaletteInline() {
        // midnight = dark card + light text; the colours must be inlined so Gmail dark-mode
        // inversion leaves it readable rather than double-inverting.
        ResolvedTemplate midnight = BuiltinTemplates.byKey("midnight");
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Body copy", "camp-1", "email", UNSUB, midnight, "Acme Events", null);
        assertThat(r.html()).contains("#0f0a1f");   // page bg
        assertThat(r.html()).contains("#1a1230");   // card bg
        assertThat(r.html()).contains("#f4f2fa");   // light text
        // no external stylesheet beyond the Google Fonts link — styles are inline
        assertThat(r.html()).contains("style=");
    }

    @Test
    void wordmarkHeaderFallsBackToOrgBrandName() {
        // classic = wordmark; with no token title it shows the org's brand name.
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Body", "camp-1", "email", UNSUB,
                BuiltinTemplates.byKey("classic"), "Tortuga Collective", null);
        assertThat(r.html()).contains("Tortuga Collective");
    }

    @Test
    void posterHeroRendersPosterImageWhenPresent() {
        ResolvedTemplate poster = BuiltinTemplates.byKey("poster");
        String posterUrl = "https://cdn.imin.wtf/posters/abc.jpg";
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Body", "camp-1", "email", UNSUB, poster, "Acme", posterUrl);
        assertThat(r.html()).contains("<img");
        assertThat(r.html()).contains(posterUrl);
    }

    @Test
    void posterHeroFallsBackToBannerWithoutPoster() {
        // No poster on the campaign → posterHero degrades to a banner header (no broken <img>).
        ResolvedTemplate poster = BuiltinTemplates.byKey("poster");
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Body", "camp-1", "email", UNSUB, poster, "Acme Events", null);
        assertThat(r.html()).doesNotContain("<img");
        // banner uses the accent colour as its background band, and shows the brand name
        assertThat(r.html()).contains("Acme Events");
    }

    @Test
    void stylesBodyLinksWithAccentColour() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "[Buy tickets](https://imin.wtf/e/x)", "camp-1", "email", UNSUB,
                BuiltinTemplates.byKey("classic"), "Acme", null);
        // classic accent is #2d5cff — the body link must carry it inline
        assertThat(r.html()).contains("color:#2d5cff");
    }

    // ---- {{tickets_button}} CTA placeholder ----

    private static final String TICKETS_URL = "https://app.imin.wtf/e/evt-123";

    @Test
    void ticketsButtonRendersAsTemplateColouredButtonWhenUrlPresent() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Come along\n\n{{tickets_button}}", "camp-1", "email", UNSUB,
                BuiltinTemplates.byKey("classic"), "Acme", null, TICKETS_URL);
        // classic buttonBg #2d5cff / buttonText #ffffff, default "Get tickets" label, real <a> (not an image)
        assertThat(r.html()).contains("background:#2d5cff");
        assertThat(r.html()).contains(">Get tickets</a>");
        assertThat(r.html()).doesNotContain("{{tickets_button}}");
        // href is UTM-tagged exactly like body links
        assertThat(r.html()).contains("utm_campaign=camp-1");
        // and the plain-text part carries the URL
        assertThat(r.text()).contains(TICKETS_URL);
    }

    @Test
    void ticketsButtonIsDroppedWhenNoUrl() {
        // No linked event ⇒ no ticketsUrl ⇒ the token renders nothing (never a fabricated link).
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Come along\n\n{{tickets_button}}", "camp-1", "email", UNSUB,
                BuiltinTemplates.byKey("classic"), "Acme", null, null);
        assertThat(r.html()).doesNotContain("Get tickets");
        assertThat(r.html()).doesNotContain("{{tickets_button}}");
        assertThat(r.text()).doesNotContain("tickets_button");
    }

    @Test
    void ticketsButtonHonoursACustomLabel() {
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Come along\n\n{{tickets_button:Grab your spot}}", "camp-1", "email", UNSUB,
                BuiltinTemplates.byKey("classic"), "Acme", null, TICKETS_URL);
        assertThat(r.html()).contains(">Grab your spot</a>");
        assertThat(r.html()).doesNotContain(">Get tickets</a>");
    }

    @Test
    void unsubscribeLinkIsNotUtmTagged() {
        // The footer's unsubscribe link is itself an imin.wtf URL; UTM rewriting runs on the
        // BODY only, so it must NOT get a utm_campaign appended.
        CampaignEmailRenderer.Rendered r = renderer.render(
                "S", "P", "Body", "camp-77", "email", UNSUB,
                BuiltinTemplates.byKey("classic"), "Acme", null);
        assertThat(r.html()).contains(UNSUB + "\"");
        assertThat(r.html()).doesNotContain(UNSUB + "?utm");
        assertThat(r.html()).doesNotContain(UNSUB + "&utm");
    }
}
