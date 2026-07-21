package com.imin.iminapi.marketing.model;

import com.imin.iminapi.marketing.template.TemplateTokens;
import com.imin.iminapi.marketing.template.TemplateTokensJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An org-saved campaign email template (V66). Builtins are NOT rows — only AI-generated
 * (and, forward-compatibly, hand-customized) org templates live here. {@code tokens} is the
 * validated {@link TemplateTokens} set, stored as JSON TEXT (H2/PG parity — see
 * {@link TemplateTokensJsonConverter}). A campaign references a saved template by putting its
 * {@code id} (as a string) in {@code campaigns.template_key}.
 */
@Entity
@Table(name = "campaign_email_templates")
@Getter
@Setter
public class CampaignEmailTemplate {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 64)
    private String name;

    @Convert(converter = TemplateTokensJsonConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private TemplateTokens tokens;

    /** 'ai' | 'custom'. Builtins never persist here (source 'builtin' is code-only). */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
