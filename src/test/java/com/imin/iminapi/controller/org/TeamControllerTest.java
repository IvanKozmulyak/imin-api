package com.imin.iminapi.controller.org;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The team list is the caller's org only; cross-org removal is owned by CrossOrgScopingTest. */
@IminIntegrationTest
class TeamControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    private User member(Organization org) {
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail(fx.email("member"));
        u.setRole(UserRole.MEMBER);
        return users.save(u);
    }

    @Test
    void the_team_list_is_the_callers_org_only() throws Exception {
        Organization own = fx.org();
        User owner = fx.owner(own);
        User ownMember = member(own);
        Organization other = fx.org();
        User otherOwner = fx.owner(other);
        User otherMember = member(other);

        String body = mvc.perform(get("/api/v1/org/team").with(as(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids).containsExactlyInAnyOrder(owner.getId().toString(), ownMember.getId().toString())
                .doesNotContain(otherOwner.getId().toString(), otherMember.getId().toString());
    }

    @Test
    void removing_an_own_member_answers_204() throws Exception {
        Organization own = fx.org();
        User owner = fx.owner(own);

        mvc.perform(delete("/api/v1/org/team/{id}", member(own).getId()).with(as(owner)))
                .andExpect(status().isNoContent());
    }
}
