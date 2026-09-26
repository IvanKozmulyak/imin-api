package com.imin.iminapi.service.ai.provenance;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiEmailDisclosureTest {

    @Test
    void none_addsNoHeadersAndNoMeta() {
        assertThat(AiEmailDisclosure.NONE.any()).isFalse();
        assertThat(AiEmailDisclosure.NONE.parts()).isEmpty();
        assertThat(AiEmailDisclosure.NONE.headers()).isEmpty();
        assertThat(AiEmailDisclosure.NONE.htmlMeta()).isEmpty();
    }

    @Test
    void subjectOnly_namesTheSubject() {
        AiEmailDisclosure d = new AiEmailDisclosure(true, false);

        assertThat(d.headers()).containsExactly(
                Map.entry("AI-Disclosure", "mode=ai-originated"),
                Map.entry("X-IMIN-AI-Generated", "subject"));
        assertThat(d.htmlMeta()).isEqualTo(
                "<meta name=\"ai-disclosure\" content=\"mode=ai-originated\"/>"
                        + "<meta name=\"imin-ai-generated\" content=\"subject\"/>");
    }

    @Test
    void bodyOnly_namesTheBody() {
        AiEmailDisclosure d = new AiEmailDisclosure(false, true);

        assertThat(d.any()).isTrue();
        assertThat(d.headers()).containsEntry("X-IMIN-AI-Generated", "body");
        assertThat(d.htmlMeta()).contains("content=\"body\"");
    }

    @Test
    void both_listsSubjectThenBody() {
        AiEmailDisclosure d = new AiEmailDisclosure(true, true);

        assertThat(d.parts()).isEqualTo("subject, body");
        assertThat(d.headers()).containsEntry("AI-Disclosure", "mode=ai-originated")
                .containsEntry("X-IMIN-AI-Generated", "subject, body");
    }
}
