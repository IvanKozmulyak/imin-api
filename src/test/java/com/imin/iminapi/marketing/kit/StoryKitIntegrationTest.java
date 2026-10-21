package com.imin.iminapi.marketing.kit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.marketing.config.MarketingChatClient;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.kit.StoryKitCaptionService.CaptionLlm;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PausableMediaStorage;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The kit through its HTTP routes on the shared context: org scoping, the poster guard, remakes and the numbers. */
@IminIntegrationTest
class StoryKitIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired DisputeRepository disputes;
    @Autowired FunnelEventRepository funnel;
    @Autowired JdbcTemplate jdbc;
    @Autowired InMemoryMediaStorage media;
    @Autowired @Qualifier(MarketingChatClient.NAME) ChatClient chat;
    @Autowired RecordingRateLimiter limiter;
    @Autowired Clock clock;
    @Autowired ShareLinkService links;
    @Autowired ShareLinkRepository linkRows;
    @Autowired MarketingEmailProperties emailProps;
    @Autowired PlatformTransactionManager transactions;

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    private Event event;
    private RequestPostProcessor auth;

    @BeforeEach
    void setUp() throws IOException {
        org = fx.org();
        orgIds.add(org.getId());
        owner = fx.owner(org);
        auth = authFor(org, owner);
        event = liveEvent(10);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    private RequestPostProcessor authFor(Organization o, User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    /** A published, public event {@code days} ahead with a real poster in our own storage. */
    private Event liveEvent(int days) throws IOException {
        Event e = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(days, ChronoUnit.DAYS));
        e.setPublishedAt(clock.instant());
        e.setVenueName("La Halle Noire");
        e.setVenueCity("Paris");
        e.setTimezone("Europe/Paris");
        e.setPosterUrl(putPoster("events/" + e.getId() + "/poster.png"));
        return events.save(e);
    }

    private String putPoster(String key) throws IOException {
        BufferedImage img = new BufferedImage(400, 500, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return media.put(key, out.toByteArray(), "image/png").url();
    }

    private String path(String suffix) {
        return "/api/v1/events/" + event.getId() + "/story-kit" + suffix;
    }

    private JsonNode make(String locale) throws Exception {
        String body = mvc.perform(post(path("")).with(auth).contentType("application/json")
                        .content("{\"locale\":\"" + locale + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private JsonNode state() throws Exception {
        return JSON.readTree(mvc.perform(get(path("")).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private Set<String> kitKeys() {
        return media.blobs().keySet().stream().filter(k -> k.startsWith("story-kits/"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private void stubCaption(CaptionLlm answer) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(CaptionLlm.class)).thenReturn(answer);
    }

    // posterUrl is organizer-writable to any string; the server must never fetch it.
    @Test
    void aPosterUrlOutsideOurStorageIsRefusedAndNothingIsFetchedOrStored() throws Exception {
        event.setPosterUrl("http://169.254.169.254/latest/meta-data");
        events.save(event);

        assertThat(state().get("blockedReason").asText()).isEqualTo("POSTER_NOT_SUPPORTED");
        mvc.perform(post(path("")).with(auth).contentType("application/json").content("{\"locale\":\"en\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.fields.reason").value("POSTER_NOT_SUPPORTED"));
        assertThat(kitKeys()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from story_kits where event_id = ?", Integer.class,
                event.getId())).isZero();
    }

    @Test
    void anotherOrganizationsEventIsA404OnEveryRoute() throws Exception {
        make("en");
        Organization other = fx.org();
        orgIds.add(other.getId());
        RequestPostProcessor stranger = authFor(other, fx.owner(other));

        mvc.perform(get(path("")).with(stranger)).andExpect(status().isNotFound());
        mvc.perform(post(path("")).with(stranger).contentType("application/json").content("{\"locale\":\"en\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(path("/caption")).with(stranger).contentType("application/json").content("{\"revision\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(path("/assets/story")).with(stranger)).andExpect(status().isNotFound());
        mvc.perform(get(path("/zip")).with(stranger)).andExpect(status().isNotFound());
    }

    @Test
    void remakeKeepsTheLinkReplacesTheFilesAndSurvivesAFailingDelete() throws Exception {
        JsonNode first = make("en");
        String code = first.at("/kit/link/code").asText();
        Set<String> firstKeys = kitKeys();
        assertThat(first.at("/kit/assets")).hasSize(5);
        assertThat(firstKeys).hasSize(5);

        ((PausableMediaStorage) media).failDeletes();
        JsonNode second = make("fr");

        assertThat(second.at("/kit/link/code").asText()).isEqualTo(code);
        assertThat(second.at("/kit/revision").asInt()).isEqualTo(1);
        assertThat(second.at("/kit/locale").asText()).isEqualTo("fr");
        // The delete failed (201 anyway), so the old files linger; the new kit points only at new files.
        Set<String> secondUrls = new TreeSet<>();
        second.at("/kit/assets").forEach(a -> secondUrls.add(a.get("url").asText()));
        assertThat(secondUrls).noneMatch(u -> firstKeys.stream().anyMatch(u::endsWith));
    }

    @Test
    void aRemakeDeletesTheOldFiles() throws Exception {
        make("en");
        Set<String> firstKeys = kitKeys();

        make("en");

        assertThat(kitKeys()).hasSize(5).doesNotContainAnyElementsOf(firstKeys);
    }

    @Test
    void aFailedRemakeDeletesItsOwnUploadsAndLeavesThePreviousKitWorking() throws Exception {
        JsonNode first = make("en");
        Set<String> firstKeys = kitKeys();

        ((PausableMediaStorage) media).failPutsAfter(2);
        mvc.perform(post(path("")).with(auth).contentType("application/json").content("{\"locale\":\"fr\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));

        assertThat(kitKeys()).isEqualTo(firstKeys);
        JsonNode after = state();
        assertThat(after.at("/kit/revision").asInt()).isEqualTo(first.at("/kit/revision").asInt());
        assertThat(after.at("/kit/locale").asText()).isEqualTo("en");
    }

    // A remake whose revision moved under it (0 rows) must not keep its files or touch the kit that won.
    @Test
    void aRemakeThatLosesTheRevisionRaceIs409AndDeletesItsOwnFiles() throws Exception {
        JsonNode first = make("en");
        Set<String> firstKeys = kitKeys();
        UUID kitId = UUID.fromString(first.at("/kit/id").asText());

        try (PgFaults.Fault lost = PgFaults.skipUpdates(jdbc, "story_kits", "id", kitId)) {
            mvc.perform(post(path("")).with(auth).contentType("application/json").content("{\"locale\":\"fr\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.fields.reason").value("KIT_CHANGED"));
        }

        assertThat(kitKeys()).isEqualTo(firstKeys);
        JsonNode after = state();
        assertThat(after.at("/kit/revision").asInt()).isEqualTo(first.at("/kit/revision").asInt());
        assertThat(after.at("/kit/locale").asText()).isEqualTo("en");
    }

    // Two first makes at once: the one saving second hits UNIQUE(event_id), answers 409 and deletes its own files.
    @Test
    void aFirstMakeThatLosesTheInsertRaceIs409AndDeletesItsOwnFiles() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        PausableMediaStorage.Pause held = ((PausableMediaStorage) media).pauseNextPut();
        try {
            Future<MockHttpServletResponse> loser = pool.submit(() -> mvc.perform(post(path("")).with(auth)
                    .contentType("application/json").content("{\"locale\":\"fr\"}")).andReturn().getResponse());
            assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
            make("en");
            Set<String> winnerKeys = kitKeys();

            held.release().countDown();
            MockHttpServletResponse res = loser.get(30, TimeUnit.SECONDS);

            assertThat(res.getStatus()).isEqualTo(409);
            assertThat(JSON.readTree(res.getContentAsString()).at("/error/fields/reason").asText()).isEqualTo("KIT_CHANGED");
            assertThat(kitKeys()).hasSize(5).isEqualTo(winnerKeys);
            assertThat(state().at("/kit/locale").asText()).isEqualTo("en");
        } finally {
            held.release().countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    // A random code that is already taken gives the new event another code; it never re-points another org's link.
    @Test
    void aShareCodeThatIsAlreadyTakenIsNeverReusedForAnotherEvent() throws Exception {
        ShareLink taken = links.getOrCreateForStoryKit(org.getId(), event.getId(), owner.getId());
        Organization otherOrg = fx.org();
        orgIds.add(otherOrg.getId());
        User otherOwner = fx.owner(otherOrg);
        Event otherEvent = fx.event(otherOrg, otherOwner, EventStatus.LIVE, clock.instant().plus(5, ChronoUnit.DAYS));
        String fresh = "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        Iterator<String> codes = List.of(taken.getCode(), fresh).iterator();
        ShareLinkService colliding = new ShareLinkService(linkRows, events, emailProps, transactions, codes::next);

        ShareLink created = colliding.getOrCreateForStoryKit(otherOrg.getId(), otherEvent.getId(), otherOwner.getId());

        assertThat(created.getCode()).isEqualTo(fresh);
        assertThat(created.getEventId()).isEqualTo(otherEvent.getId());
        ShareLink old = linkRows.findById(taken.getCode()).orElseThrow();
        assertThat(old.getEventId()).isEqualTo(event.getId());
        assertThat(old.getOrgId()).isEqualTo(org.getId());
        assertThat(old.getUtmCampaign()).isEqualTo("kit_" + taken.getCode());
    }

    // Image and zip downloads draw on their own per-user bucket.
    @ParameterizedTest
    @ValueSource(strings = {"/assets/story", "/zip"})
    void downloadsConsumeTheDownloadBucket(String route) throws Exception {
        make("en");
        limiter.limit("story-kit-download", 1);

        mvc.perform(get(path(route)).with(auth)).andExpect(status().isOk());
        mvc.perform(get(path(route)).with(auth)).andExpect(status().isTooManyRequests());
    }

    // Making a kit and writing its caption each draw on their own per-user bucket.
    @ParameterizedTest
    @CsvSource({"'', story-kit", "/caption, story-kit-caption"})
    void makingAndCaptioningConsumeTheirBuckets(String route, String bucket) throws Exception {
        int revision = make("en").at("/kit/revision").asInt();
        stubCaption(new CaptionLlm("Hard techno.", List.of()));
        String body = route.isEmpty() ? "{\"locale\":\"en\"}" : "{\"revision\":" + revision + "}";
        limiter.reset(); // the setup make already counted against story-kit
        limiter.limit(bucket, 1);

        mvc.perform(post(path(route)).with(auth).contentType("application/json").content(body))
                .andExpect(route.isEmpty() ? status().isCreated() : status().isOk());
        mvc.perform(post(path(route)).with(auth).contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests());
    }

    // A remake that lands while the model writes must not get the old caption attached.
    @Test
    void aCaptionForARevisionThatMovedOnIsRefusedAndTheRemakeClearsOldCaptions() throws Exception {
        JsonNode first = make("en");
        int rev = first.at("/kit/revision").asInt();
        stubCaption(new CaptionLlm("Hard techno.", List.of()));
        mvc.perform(post(path("/caption")).with(auth).contentType("application/json")
                        .content("{\"revision\":" + rev + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aiGenerated").value(true));
        assertThat(state().at("/kit/caption/text").asText()).contains("Hard techno.");

        JsonNode remade = make("fr");

        assertThat(remade.at("/kit/caption").isNull()).isTrue();
        mvc.perform(post(path("/caption")).with(auth).contentType("application/json")
                        .content("{\"revision\":" + rev + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.fields.reason").value("KIT_CHANGED"));
        assertThat(state().at("/kit/caption").isNull()).isTrue();
    }

    @Test
    void aRemakeDuringTheModelCallWinsOverTheCaptionSave() throws Exception {
        int rev = make("en").at("/kit/revision").asInt();
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(CaptionLlm.class)).thenAnswer(inv -> {
            make("fr");
            return new CaptionLlm("Old language caption.", List.of());
        });

        mvc.perform(post(path("/caption")).with(auth).contentType("application/json")
                        .content("{\"revision\":" + rev + "}"))
                .andExpect(status().isConflict());

        JsonNode now = state();
        assertThat(now.at("/kit/locale").asText()).isEqualTo("fr");
        assertThat(now.at("/kit/caption").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "none     | ''",
            "startsAt | date",
            "timezone | date",
            "venue    | venue",
            "city     | venue",
            "poster   | poster",
    })
    void changingTheEventAfterTheKitMarksWhatIsStale(String change, String expected) throws Exception {
        make("en");
        Event e = events.findById(event.getId()).orElseThrow();
        switch (change) {
            case "startsAt" -> e.setStartsAt(e.getStartsAt().plus(1, ChronoUnit.HOURS));
            case "timezone" -> e.setTimezone("America/New_York");
            case "venue" -> e.setVenueName("Another Hall");
            case "city" -> e.setVenueCity("Lyon");
            case "poster" -> e.setPosterUrl(putPoster("events/" + e.getId() + "/poster-2.png"));
            default -> { }
        }
        events.save(e);

        List<String> stale = new ArrayList<>();
        state().at("/kit/stale").forEach(n -> stale.add(n.asText()));

        assertThat(stale).containsExactlyElementsOf(expected.replace("'", "").isBlank() ? List.of() : List.of(expected));
    }

    private Order order(Event on, String campaign, boolean testMode, long total) {
        Order o = fx.order(on, fx.email("buyer"));
        o.setTotalMinor(total);
        o.setUtmCampaign(campaign);
        o.setTestMode(testMode);
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        return orders.save(o);
    }

    private void beacon(Event on, String anon, String campaign) {
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(on.getId());
        fe.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        fe.setAnonId(anon);
        fe.setUtmCampaign(campaign);
        funnel.save(fe);
    }

    // Excluded rows are created first so a missing filter would let them lead the sums.
    @Test
    void statsCountOnlyLiveKeptOrdersAndVisitorsOfThisEventAndCampaign() throws Exception {
        String code = make("en").at("/kit/link/code").asText();
        String campaign = "kit_" + code;
        Event otherEvent = liveEvent(20);

        order(event, campaign, true, 9_900);                       // test mode
        Order refunded = order(event, campaign, false, 7_700);     // refunded in full
        Refund r = new Refund();
        r.setOrderId(refunded.getId());
        r.setStripePaymentIntentId(refunded.getStripePaymentIntentId());
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(7_700);
        r.setCurrency("EUR");
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refunds.save(r);
        Order lost = order(event, campaign, false, 5_500);         // chargeback lost
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        d.setOrgId(org.getId());
        d.setEventId(event.getId());
        d.setOrderId(lost.getId());
        d.setAmountMinor(5_500);
        d.setCurrency("EUR");
        d.setStatus(DisputeStatus.LOST);
        disputes.save(d);
        order(otherEvent, campaign, false, 3_300);                 // same campaign, another event
        order(event, "kit_other", false, 2_200);                   // this event, another campaign
        order(event, campaign, false, 1_500);                      // the one that counts

        beacon(otherEvent, "anonC", campaign);
        beacon(event, "anonD", "kit_other");
        beacon(event, "anonA", campaign);
        beacon(event, "anonA", campaign);
        beacon(event, "anonB", campaign);

        JsonNode stats = state().at("/kit/stats");

        assertThat(stats.get("orders").asLong()).isEqualTo(1);
        assertThat(stats.get("netRevenueMinor").asLong()).isEqualTo(1_500);
        assertThat(stats.get("visitors").asLong()).isEqualTo(2);
        assertThat(stats.get("currency").asText()).isEqualTo("EUR");
    }

    private Set<String> zipEntries(byte[] zip) throws IOException {
        Set<String> names = new TreeSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        return names;
    }

    private String zipText(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals(name)) return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void zipHoldsExactlyTheMadeImagesTheCaptionWhenThereIsOneAndTheLink() throws Exception {
        event = liveEvent(4);   // 7 days out is gone: no 1-week frame
        JsonNode made = make("en");
        assertThat(made.at("/kit/skipped/0/key").asText()).isEqualTo("d7");

        MockHttpServletResponse withoutCaption = mvc.perform(get(path("/zip")).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(withoutCaption.getHeader("Content-Disposition")).contains("instagram-kit-" + event.getSlug() + ".zip");
        assertThat(zipEntries(withoutCaption.getContentAsByteArray())).containsExactly(
                "countdown-2-days.jpg", "countdown-day-of.jpg", "feed-post.jpg", "link.txt", "story.jpg");
        assertThat(zipText(withoutCaption.getContentAsByteArray(), "link.txt"))
                .isEqualTo("https://app.imin.wtf/s/" + made.at("/kit/link/code").asText() + "\n");

        stubCaption(new CaptionLlm("Hard techno.", List.of()));
        mvc.perform(post(path("/caption")).with(auth).contentType("application/json")
                .content("{\"revision\":" + made.at("/kit/revision").asInt() + "}")).andExpect(status().isOk());
        byte[] withCaption = mvc.perform(get(path("/zip")).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntries(withCaption)).contains("caption.txt");
        assertThat(zipText(withCaption, "caption.txt")).startsWith("Hard techno.");
    }

    @Test
    void anAssetIsStreamedAsAJpegOfTheStoryFormat() throws Exception {
        make("en");

        byte[] bytes = mvc.perform(get(path("/assets/story")).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img.getWidth()).isEqualTo(1080);
        assertThat(img.getHeight()).isEqualTo(1920);
        mvc.perform(get(path("/assets/nope")).with(auth)).andExpect(status().isNotFound());
    }
}
