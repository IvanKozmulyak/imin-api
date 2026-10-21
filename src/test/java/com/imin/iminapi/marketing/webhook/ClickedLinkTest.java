package com.imin.iminapi.marketing.webhook;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Only the campaign event's own buyer page is a ticket click, and neither unsubscribe link is ever counted as a click. */
class ClickedLinkTest {

    private static final String BUYER = "https://app.imin.wtf";
    private static final String API = "https://api.imin.wtf";
    private static final UUID EVENT = UUID.fromString("5b1f0e3c-2a4d-4c8e-9f10-0123456789ab");
    private static final UUID OTHER_EVENT = UUID.fromString("9c2e1d4b-3b5e-4d9f-8a21-ba9876543210");

    static Stream<Arguments> links() {
        String utm = "?utm_source=imin&utm_medium=email&utm_campaign=c1";
        return Stream.of(
                arguments("event page with UTM tags", BUYER + "/e/" + EVENT + utm, EVENT, ClickedLink.TICKET),
                arguments("a page below the event page", BUYER + "/e/" + EVENT + "/buy", EVENT, ClickedLink.TICKET),
                arguments("upper-case host", "https://APP.IMIN.WTF/e/" + EVENT + utm, EVENT, ClickedLink.TICKET),
                arguments("another event's page", BUYER + "/e/" + OTHER_EVENT + utm, EVENT, ClickedLink.OTHER),
                arguments("event id with a suffix", BUYER + "/e/" + EVENT + "x", EVENT, ClickedLink.OTHER),
                arguments("buyer-site root", BUYER + "/", EVENT, ClickedLink.OTHER),
                arguments("event path on another host", "https://evil.example/e/" + EVENT, EVENT, ClickedLink.OTHER),
                arguments("external URL", "https://instagram.com/imin", EVENT, ClickedLink.OTHER),
                arguments("unsubscribe URL", API + "/api/v1/public/unsubscribe/tok.en_42", EVENT,
                        ClickedLink.UNSUBSCRIBE),
                arguments("unsubscribe path on another host", "https://old-api.example/api/v1/public/unsubscribe/tok",
                        EVENT, ClickedLink.UNSUBSCRIBE),
                arguments("buyer-site unsubscribe page", BUYER + "/unsubscribe/tok.en_42", EVENT,
                        ClickedLink.UNSUBSCRIBE),
                arguments("buyer-site unsubscribe page, upper-case host", "https://APP.IMIN.WTF/unsubscribe/tok", EVENT,
                        ClickedLink.UNSUBSCRIBE),
                arguments("unsubscribe page path on another host", "https://evil.example/unsubscribe/tok", EVENT,
                        ClickedLink.OTHER),
                arguments("buyer-site path that only starts like unsubscribe", BUYER + "/unsubscribed/tok", EVENT,
                        ClickedLink.OTHER),
                arguments("host-less event path", "/e/" + EVENT, EVENT, ClickedLink.UNKNOWN),
                arguments("mailto", "mailto:hi@imin.wtf", EVENT, ClickedLink.UNKNOWN),
                arguments("null", null, EVENT, ClickedLink.UNKNOWN),
                arguments("blank", "  ", EVENT, ClickedLink.UNKNOWN),
                arguments("malformed", "https://app imin.wtf/e/%%", EVENT, ClickedLink.UNKNOWN),
                arguments("campaign without an event", BUYER + "/e/" + EVENT, null, ClickedLink.OTHER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("links")
    void classifiesTheClickedLink(String name, String link, UUID campaignEventId, ClickedLink expected) {
        assertThat(ClickedLink.classify(link, campaignEventId, BUYER, API)).isEqualTo(expected);
    }
}
