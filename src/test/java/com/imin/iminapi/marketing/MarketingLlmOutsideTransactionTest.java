package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.config.MarketingChatClient;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsLlm;
import com.imin.iminapi.marketing.dto.GeneratedTemplateLlm;
import com.imin.iminapi.marketing.dto.SubjectVariantsLlm;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A stalled model answer must not hold a database connection: each marketing-copy endpoint calls the model with
 * no transaction open, and still stores what it produced afterwards.
 */
@IminIntegrationTest
class MarketingLlmOutsideTransactionTest {

    @Autowired MockMvc mvc;
    @Autowired @Qualifier(MarketingChatClient.NAME) ChatClient chat;
    @Autowired CampaignRepository campaigns;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID orgId : orgIds) jdbc.update("DELETE FROM campaign_email_templates WHERE org_id = ?", orgId);
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, orgIds);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"compose-variants", "subject-variants", "email-templates/generate"})
    void theModelIsCalledWithNoTransactionOpen(String endpoint) throws Exception {
        Organization org = fx.org();
        orgIds.add(org.getId());
        AuthPrincipal owner = fx.principal(fx.owner(org));
        Campaign campaign = draft(org.getId());
        List<Boolean> transactionOpenAtCall = stubModel();

        String path = endpoint.startsWith("email-templates")
                ? "/api/v1/marketing/" + endpoint
                : "/api/v1/marketing/campaigns/" + campaign.getId() + "/" + endpoint;
        mvc.perform(post(path).with(authentication(new UsernamePasswordAuthenticationToken(owner, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + owner.role().name())))))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(transactionOpenAtCall).containsExactly(false);
        // What the model produced is still stored after the call: fingerprints, or the generated template.
        String stored = endpoint.startsWith("email-templates")
                ? "SELECT COUNT(*) FROM campaign_email_templates WHERE org_id = ?"
                : "SELECT COUNT(*) FROM campaign_ai_suggestions s JOIN campaigns c ON c.id = s.campaign_id"
                        + " WHERE c.org_id = ?";
        assertThat(jdbc.queryForObject(stored, Integer.class, org.getId())).isPositive();
    }

    /** Answers every prompt with usable copy and records whether a transaction was open at the call. */
    private List<Boolean> stubModel() {
        List<Boolean> seen = new CopyOnWriteArrayList<>();
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenAnswer(inv -> {
            seen.add(TransactionSynchronizationManager.isActualTransactionActive());
            return call;
        });
        when(call.entity(EmailComposeVariantsLlm.class)).thenReturn(new EmailComposeVariantsLlm(List.of(
                new EmailComposeVariantsLlm.Variant("Doors at ten", "See you there", "Come along."))));
        when(call.entity(SubjectVariantsLlm.class)).thenReturn(
                new SubjectVariantsLlm(List.of("Doors at ten", "Last call", "One night only")));
        when(call.entity(GeneratedTemplateLlm.class)).thenReturn(new GeneratedTemplateLlm("Night style", null));
        return seen;
    }

    private Campaign draft(UUID orgId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Launch night");
        c.setStatus("draft");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }
}
