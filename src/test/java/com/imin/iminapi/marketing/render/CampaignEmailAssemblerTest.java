package com.imin.iminapi.marketing.render;

import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.marketing.template.BuiltinTemplates;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the preview tells the organizer the renderer leaves out, and how it lists the email's links. */
class CampaignEmailAssemblerTest {

    private final EventRepository events = mock(EventRepository.class);
    private final CampaignTemplateService templates = mock(CampaignTemplateService.class);
    private final MarketingEmailProperties props = new MarketingEmailProperties();
    private final CampaignEmailAssembler assembler = new CampaignEmailAssembler(
            new CampaignEmailRenderer(new UtmLinkRewriter()), templates, events, props);

    private Campaign campaign(String templateKey, UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setTemplateKey(templateKey);
        c.setEventId(eventId);
        when(templates.resolve(any(), any())).thenReturn(BuiltinTemplates.byKey(templateKey));
        return c;
    }

    private Event event(Campaign c, String posterUrl) {
        Event e = new Event();
        e.setId(c.getEventId());
        e.setOrgId(c.getOrgId());
        e.setPosterUrl(posterUrl);
        return e;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Poster template, event without a poster | poster  | present | ''         | Hi                 | POSTER_MISSING",
            "tickets button with no event linked     | classic | none    | ''         | '{{tickets_button}}' | TICKETS_BUTTON_DROPPED",
            "linked event that cannot be loaded      | poster  | missing | ''         | '{{tickets_button}}' | EVENT_UNAVAILABLE",
            "poster and event both there             | poster  | present | poster.png | '{{tickets_button}}' | ''"})
    void notices(String label, String templateKey, String eventState, String posterUrl, String bodyMd, String expected) {
        Campaign c = campaign(templateKey, "none".equals(eventState) ? null : UUID.randomUUID());
        if (c.getEventId() != null) {
            when(events.findActive(c.getEventId())).thenReturn("present".equals(eventState)
                    ? Optional.of(event(c, posterUrl.isEmpty() ? null : "https://cdn.imin.wtf/" + posterUrl))
                    : Optional.empty());
        }

        List<String> notices = CampaignEmailAssembler.notices(assembler.contextFor(c, null), bodyMd);

        assertThat(notices).containsExactlyElementsOf(expected.isEmpty() ? List.of() : List.of(expected));
    }

    @Test
    void linksWithholdTheUnsubscribePlaceholder() {
        Campaign c = campaign("classic", UUID.randomUUID());
        when(events.findActive(c.getEventId())).thenReturn(Optional.of(event(c, null)));
        String unsub = "https://app.imin.wtf/unsubscribe/preview-token";
        String tag = "utm_source=imin&utm_medium=email&utm_campaign=" + c.getId();

        CampaignEmailAssembler.Email email = assembler.render(assembler.contextFor(c, null), "S", null,
                "See [the **line-up**](https://app.imin.wtf/lineup?day=fri&room=2)\n\n{{tickets_button:Book now}}",
                null, unsub, null);

        assertThat(CampaignEmailAssembler.links(email.html(), unsub)).containsExactly(
                new CampaignEmailAssembler.Link("link", "the line-up", "https://app.imin.wtf/lineup?day=fri&room=2&" + tag),
                new CampaignEmailAssembler.Link("link", "Book now", "https://app.imin.wtf/e/" + c.getEventId() + "?" + tag),
                new CampaignEmailAssembler.Link("unsubscribe", "Unsubscribe", null));
    }
}
