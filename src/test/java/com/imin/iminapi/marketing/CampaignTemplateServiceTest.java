package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.EmailTemplateDto;
import com.imin.iminapi.marketing.dto.GeneratedTemplateLlm;
import com.imin.iminapi.marketing.dto.GenerateTemplateRequest;
import com.imin.iminapi.marketing.model.CampaignEmailTemplate;
import com.imin.iminapi.marketing.repository.CampaignEmailTemplateRepository;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.marketing.template.TemplateHeader;
import com.imin.iminapi.marketing.template.TemplatePalette;
import com.imin.iminapi.marketing.template.TemplateTokens;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CampaignTemplateServiceTest {

    // Deep stubs cover chat.prompt().user(...).call().entity(GeneratedTemplateLlm.class).
    private final ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final CampaignEmailTemplateRepository templates = mock(CampaignEmailTemplateRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);

    private final CampaignTemplateService sut =
            new CampaignTemplateService(chat, templates, events, organizations);

    private final UUID orgId = UUID.randomUUID();

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Organization org() {
        Organization o = new Organization();
        o.setName("Tortuga Collective");
        return o;
    }

    private void stubModel(GeneratedTemplateLlm out) {
        when(chat.prompt().user(anyString()).call().entity(GeneratedTemplateLlm.class)).thenReturn(out);
    }

    private static TemplateTokens tokens(String bg) {
        return new TemplateTokens(
                new TemplatePalette(bg, "#ffffff", "#111111", "#2d5cff", "#666666", "#2d5cff", "#ffffff"),
                new TemplateHeader("wordmark", null),
                "Arial, sans-serif");
    }

    // ---- listing ----

    @Test
    void listReturnsTheFourBuiltinsFirst() {
        when(templates.findByOrgIdOrderByCreatedAtDesc(orgId)).thenReturn(List.of());
        List<EmailTemplateDto> list = sut.list(principal());
        assertThat(list).hasSize(4);
        assertThat(list).allMatch(EmailTemplateDto::builtin);
        assertThat(list).extracting(EmailTemplateDto::key)
                .containsExactly("classic", "midnight", "poster", "mono");
    }

    @Test
    void listAppendsSavedOrgTemplatesAfterBuiltins() {
        CampaignEmailTemplate row = new CampaignEmailTemplate();
        row.setId(UUID.randomUUID());
        row.setOrgId(orgId);
        row.setName("Neon nights");
        row.setSource(ResolvedTemplate.SOURCE_AI);
        row.setTokens(tokens("#101010"));
        when(templates.findByOrgIdOrderByCreatedAtDesc(orgId)).thenReturn(List.of(row));

        List<EmailTemplateDto> list = sut.list(principal());
        assertThat(list).hasSize(5);
        EmailTemplateDto last = list.get(4);
        assertThat(last.builtin()).isFalse();
        assertThat(last.key()).isEqualTo(row.getId().toString());
        assertThat(last.source()).isEqualTo("ai");
    }

    // ---- generate ----

    @Test
    void generateValidatesAndSavesGoodTokens() {
        when(organizations.findById(orgId)).thenReturn(Optional.of(org()));
        stubModel(new GeneratedTemplateLlm("Sunset", tokens("#fef3e2")));
        when(templates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailTemplateDto dto = sut.generate(principal(), new GenerateTemplateRequest(null, "warm and editorial"));

        assertThat(dto.source()).isEqualTo("ai");
        assertThat(dto.builtin()).isFalse();
        assertThat(dto.name()).isEqualTo("Sunset");
        assertThat(dto.tokens().palette().bg()).isEqualTo("#fef3e2");
        verify(templates).save(any());
    }

    @Test
    void generateRejectsNonHexColour() {
        when(organizations.findById(orgId)).thenReturn(Optional.of(org()));
        // "red" is not a hex value — the validator must 422 and nothing gets saved.
        stubModel(new GeneratedTemplateLlm("Bad", tokens("red")));

        assertThatThrownBy(() -> sut.generate(principal(), new GenerateTemplateRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(templates, never()).save(any());
    }

    @Test
    void generateFallsBackToClassicTokensWhenModelFails() {
        when(organizations.findById(orgId)).thenReturn(Optional.of(org()));
        when(chat.prompt().user(anyString()).call().entity(GeneratedTemplateLlm.class))
                .thenThrow(new RuntimeException("model down"));
        when(templates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmailTemplateDto dto = sut.generate(principal(), new GenerateTemplateRequest(null, null));

        // Degrades to classic tokens (never a 5xx), still saved as an org 'ai' template.
        assertThat(dto.source()).isEqualTo("ai");
        assertThat(dto.tokens().palette().bg()).isEqualTo("#f4f2fa"); // classic bg
        // Name falls back to "<org> style" when the model gave none.
        assertThat(dto.name()).isEqualTo("Tortuga Collective style");
    }

    // ---- resolve ----

    @Test
    void resolveBuiltinKeyReturnsThatBuiltin() {
        ResolvedTemplate r = sut.resolve(orgId, "midnight");
        assertThat(r.key()).isEqualTo("midnight");
        assertThat(r.isBuiltin()).isTrue();
    }

    @Test
    void resolveNullOrUnknownKeyFallsBackToClassic() {
        assertThat(sut.resolve(orgId, null).key()).isEqualTo("classic");
        assertThat(sut.resolve(orgId, "does-not-exist").key()).isEqualTo("classic");
    }

    @Test
    void resolveDeletedOrgTemplateFallsBackToClassic() {
        UUID missing = UUID.randomUUID();
        when(templates.findByIdAndOrgId(missing, orgId)).thenReturn(Optional.empty());
        assertThat(sut.resolve(orgId, missing.toString()).key()).isEqualTo("classic");
    }

    @Test
    void resolveLoadsSavedOrgTemplateByUuid() {
        UUID id = UUID.randomUUID();
        CampaignEmailTemplate row = new CampaignEmailTemplate();
        row.setId(id);
        row.setOrgId(orgId);
        row.setName("Saved");
        row.setSource(ResolvedTemplate.SOURCE_AI);
        row.setTokens(tokens("#123456"));
        when(templates.findByIdAndOrgId(id, orgId)).thenReturn(Optional.of(row));

        ResolvedTemplate r = sut.resolve(orgId, id.toString());
        assertThat(r.key()).isEqualTo(id.toString());
        assertThat(r.tokens().palette().bg()).isEqualTo("#123456");
    }
}
