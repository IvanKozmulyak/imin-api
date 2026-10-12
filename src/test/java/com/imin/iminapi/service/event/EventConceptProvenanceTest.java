package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A draft created from an AI Studio concept records its AI provenance; a foreign concept id is a no-leak 404. */
@IminIntegrationTest
class EventConceptProvenanceTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired GeneratedEventRepository generated;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Authentication auth;
    private UUID ownConcept;
    private UUID foreignConcept;

    @BeforeEach
    void setUp() {
        org = fx.org();
        User owner = fx.owner(org);
        auth = new UsernamePasswordAuthenticationToken(fx.principal(owner), null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        ownConcept = concept(org.getId());
        foreignConcept = concept(fx.org().getId());
    }

    enum Source { OWN, FOREIGN, ABSENT }

    @ParameterizedTest
    @EnumSource(Source.class)
    void create_records_concept_provenance_only_for_an_own_concept(Source source) throws Exception {
        String name = "Draft " + UUID.randomUUID();
        String conceptField = switch (source) {
            case OWN -> ", \"sourceConceptId\": \"" + ownConcept + "\"";
            case FOREIGN -> ", \"sourceConceptId\": \"" + foreignConcept + "\"";
            case ABSENT -> "";
        };

        mvc.perform(post("/api/v1/events").with(authentication(auth))
                        .contentType("application/json")
                        .content("{\"name\": \"" + name + "\"" + conceptField + "}"))
                .andExpect(source == Source.FOREIGN ? status().isNotFound() : status().isCreated());

        List<Boolean> stamps = jdbc.queryForList(
                "select concept_ai_generated from events where org_id = ? and name = ?",
                Boolean.class, org.getId(), name);
        switch (source) {
            case OWN -> assertThat(stamps).containsExactly(true);
            case FOREIGN -> assertThat(stamps).as("no draft for a foreign concept").isEmpty();
            case ABSENT -> assertThat(stamps).as("absence is unknown, never 'manual'").containsExactly((Boolean) null);
        }
    }

    private UUID concept(UUID orgId) {
        GeneratedEvent g = new GeneratedEvent();
        g.setOrgId(orgId);
        g.setVibe("warehouse");
        g.setStatus(GeneratedEventStatus.COMPLETE);
        g.setCreatedAt(LocalDateTime.now());
        Concept c = new Concept();
        c.setGeneratedEvent(g);
        c.setTitle("Warehouse Mass");
        c.setSortOrder(0);
        g.getConcepts().add(c);
        return generated.save(g).getConcepts().get(0).getId();
    }
}
