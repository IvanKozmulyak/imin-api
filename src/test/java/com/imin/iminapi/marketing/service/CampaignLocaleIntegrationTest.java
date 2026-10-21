package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.CreateCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The language a campaign is created with, and where it is carried: create, duplicate, Momentum, hub default, backfill. */
@IminIntegrationTest
class CampaignLocaleIntegrationTest {

    @Autowired CampaignService campaigns;
    @Autowired MarketingHubService hub;
    @Autowired com.imin.iminapi.marketing.service.MomentumService momentum;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired jakarta.persistence.EntityManager em;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        CampaignRows.delete(jdbc, orgIds);
        for (UUID id : orgIds) jdbc.update("delete from momentum_suggestions where org_id = ?", id);
        OrgRows.delete(jdbc, orgIds);
    }

    private record Own(Organization org, User owner, AuthPrincipal principal) {}

    private Own own(String country, String userLocale) {
        Organization org = fx.org();
        org.setCountry(country);
        org = orgs.save(org);
        User owner = fx.owner(org);
        owner.setLocale(userLocale);
        owner = users.save(owner);
        orgIds.add(org.getId());
        return new Own(org, owner, fx.principal(owner));
    }

    private static CreateCampaignRequest request(String locale) {
        return new CreateCampaignRequest("email", "Launch", null, null, null, null, null, null, null, null, locale);
    }

    @Test
    void createUsesTheOrgDefault_explicitWins_unsupportedIs400() throws Exception {
        Own fr = own("FR", null);
        Own de = own("DE", "uk");

        assertThat(campaigns.create(fr.principal(), request(null)).locale()).isEqualTo("fr");
        assertThat(campaigns.create(de.principal(), request(null)).locale()).isEqualTo("uk");
        assertThat(campaigns.create(fr.principal(), request("es")).locale()).isEqualTo("es");

        mvc.perform(post("/api/v1/marketing/campaigns")
                        .with(authentication(new UsernamePasswordAuthenticationToken(fr.principal(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_OWNER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"email\",\"name\":\"x\",\"locale\":\"de\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.locale").exists());
        assertThat(jdbc.queryForObject("select count(*) from campaigns where org_id = ? and name = 'x'",
                Integer.class, fr.org().getId())).isZero();
    }

    @Test
    void patchChangesTheLanguageOfADraft() {
        Own de = own("DE", null);
        CampaignDto draft = campaigns.create(de.principal(), request(null));
        assertThat(draft.locale()).isEqualTo("en");

        CampaignDto patched = campaigns.patch(de.principal(), draft.id(),
                new PatchCampaignRequest(null, null, null, null, null, null, null, null, null, null, "uk"));

        assertThat(patched.locale()).isEqualTo("uk");
        assertThat(locale(draft.id())).isEqualTo("uk");
    }

    @Test
    void duplicateAndMomentumDraftsCarryALanguage() {
        Own fr = own("FR", null);
        CampaignDto uk = campaigns.create(fr.principal(), request("uk"));
        assertThat(campaigns.duplicate(fr.principal(), uk.id()).locale()).isEqualTo("uk");

        // A draft written in a language keeps it; an older draft without one was written in English.
        Own de = own("DE", null);
        UUID event = fx.event(de.org(), de.owner(), EventStatus.LIVE, Instant.now().plusSeconds(86_400)).getId();
        assertThat(approve(de, event, "\"locale\":\"es\",").locale()).isEqualTo("es");
        UUID frEvent = fx.event(fr.org(), fr.owner(), EventStatus.LIVE, Instant.now().plusSeconds(86_400)).getId();
        assertThat(approve(fr, frEvent, "").locale()).isEqualTo("en");
    }

    private CampaignDto approve(Own own, UUID eventId, String localeJson) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(own.org().getId());
        s.setEventId(eventId);
        s.setTriggerType("launch_push");
        s.setStatus("suggested");
        s.setMetricsSnapshot("{}");
        s.setDraftPayload("{" + localeJson + "\"subject\":\"S\",\"preheader\":\"p\",\"bodyMd\":\"b\",\"segmentId\":null,"
                + "\"posterUrl\":null,\"why\":\"w\"}");
        s.setSuggestedAt(Instant.now());
        suggestions.save(s);
        return momentum.approve(own.principal(), s.getId());
    }

    @Test
    void hubMetricsOffersTheDefaultLanguage() {
        assertThat(hub.metrics(own("MC", null).principal()).defaultCampaignLocale()).isEqualTo("fr");
        assertThat(hub.metrics(own("DE", "es").principal()).defaultCampaignLocale()).isEqualTo("es");
    }

    /** Re-runs the migration's UPDATE over one campaign per status; rolled back with the test. */
    @Test
    @Transactional
    void backfillKeepsSentCampaignsEnglishAndDraftsFollowCountry() throws Exception {
        String migration = new String(new ClassPathResource("db/migration/V193__campaigns_locale.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String update = migration.substring(migration.indexOf("UPDATE campaigns"));

        UUID fr = orgWithCountry("FR");
        UUID mc = orgWithCountry("MC");
        UUID ua = orgWithCountry("UA");
        UUID es = orgWithCountry("ES");
        UUID de = orgWithCountry("DE");
        UUID none = orgWithCountry(null);
        List<UUID> drafts = List.of(
                campaign(fr, "draft"), campaign(mc, "draft"), campaign(ua, "draft"),
                campaign(es, "draft"), campaign(de, "draft"), campaign(none, "draft"));
        UUID momentumDraft = campaign(fr, "draft");
        jdbc.update("update campaigns set origin = 'momentum' where id = ?", momentumDraft);
        List<UUID> others = new ArrayList<>();
        for (String status : List.of("scheduled", "sending", "sent", "failed", "canceled")) {
            others.add(campaign(fr, status));
        }

        em.flush();
        jdbc.update(update);

        List<String> draftLocales = new ArrayList<>();
        for (UUID id : drafts) draftLocales.add(locale(id));
        assertThat(draftLocales).containsExactly("fr", "fr", "uk", "es", "en", "en");
        for (UUID id : others) assertThat(locale(id)).isEqualTo("en");
        assertThat(locale(momentumDraft)).isEqualTo("en");
    }

    private UUID orgWithCountry(String country) {
        Organization org = fx.org();
        org.setCountry(country);
        return orgs.save(org).getId();
    }

    private UUID campaign(UUID orgId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into campaigns (id, org_id, channel, name, status, created_at, updated_at) "
                + "values (?, ?, 'email', 'Backfill', ?, now(), now())", id, orgId, status);
        return id;
    }

    private String locale(UUID campaignId) {
        return jdbc.queryForObject("select locale from campaigns where id = ?", String.class, campaignId);
    }
}
