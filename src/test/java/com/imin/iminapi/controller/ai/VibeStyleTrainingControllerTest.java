package com.imin.iminapi.controller.ai;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.poster.RecraftClient;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Training spends Recraft credits and rewrites the platform-wide vibe_style row, so it is ADMIN+ only. */
@IminIntegrationTest
class VibeStyleTrainingControllerTest {

    private static final String VIBE = "brutalist_techno";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;
    @Autowired RecraftClient recraft;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    @Test
    void a_member_is_forbidden_before_any_recraft_call() throws Exception {
        Organization org = fx.org();
        User member = new User();
        member.setOrgId(org.getId());
        member.setEmail(fx.email("member"));
        member.setRole(UserRole.MEMBER);
        member = users.save(member);

        mvc.perform(post("/api/v1/ai/vibes/{id}/train-style", VIBE).with(as(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(recraft);
    }

    /** Rolled back: the vibe_style row is shared by every org's posters. */
    @Test
    @Transactional
    void an_owner_trains_the_vibe_style_through_recraft() throws Exception {
        User owner = fx.owner(fx.org());
        when(recraft.createStyle(anyList())).thenReturn("style-trained-001");

        mvc.perform(post("/api/v1/ai/vibes/{id}/train-style", VIBE).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vibeId").value(VIBE))
                .andExpect(jsonPath("$.provider").value("RECRAFT"))
                .andExpect(jsonPath("$.styleId").value("style-trained-001"));
    }
}
