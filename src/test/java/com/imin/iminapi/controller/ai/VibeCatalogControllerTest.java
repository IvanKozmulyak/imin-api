package com.imin.iminapi.controller.ai;

import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The AI Studio vibe picker catalog. */
@IminIntegrationTest
class VibeCatalogControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;

    @Test
    void the_catalog_is_served_to_an_organizer() throws Exception {
        User owner = fx.owner(fx.org());

        mvc.perform(get("/api/v1/ai/vibes").with(authentication(new UsernamePasswordAuthenticationToken(
                        fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }
}
