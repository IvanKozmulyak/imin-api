package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.sources.openevents.GenreMatcher.Match;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenreMatcherTest {

    private static final String BUCKETS = QuestionBank.GENRE_BUCKETS.stream()
            .filter(b -> !b.equals("house & techno") && !b.equals("jazz & acoustic"))
            .map(b -> "  \"" + b + "\": [x" + Math.abs(b.hashCode()) + "]")
            .collect(Collectors.joining("\n"));

    private static final String VALID = """
            version: 1
            buckets:
              "house & techno": [techno, house, deep house]
              "jazz & acoustic": [jazz, jazz manouche, techno]
            %s
            exclude_phrases: [open house, portes ouvertes]
            local_events: [carnaval, fete de la musique, braderie]
            """.formatted(BUCKETS);

    private static GenreMatcher parse(String yaml) {
        return GenreMatcher.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejects(String yaml, String fragment) {
        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(IllegalStateException.class).hasMessageContaining(fragment);
    }

    private final GenreMatcher matcher = parse(VALID);

    @Test
    void shippedFileLoads() {
        GenreMatcher shipped = GenreMatcher.load(new DefaultResourceLoader());

        assertThat(shipped.buckets().keySet()).containsExactlyElementsOf(QuestionBank.GENRE_BUCKETS);
        assertThat(shipped.match("Nono La Grinta", List.of("concert", "rap")).genres()).containsExactly("hip-hop & r&b");
        assertThat(shipped.match("Braderie de Lille", List.of()).community()).isTrue();
        assertThat(shipped.match("Exposition André Copin", List.of("peinture")).matched()).isFalse();
    }

    @Test
    void shippedListsSkipLiveFalsePositives() {
        GenreMatcher shipped = GenreMatcher.load(new DefaultResourceLoader());

        // live titles seen 2026-10-01 that bare keywords used to match
        assertThat(shipped.match("Didier Mahieu - under soul ou over soul", List.of()).matched()).isFalse();
        assertThat(shipped.match("Initiation à la danse West Coast Swing", List.of()).matched()).isFalse();
        assertThat(shipped.match("PUNK.E.S ou comment nous ne sommes pas devenues célèbres", List.of()).matched()).isFalse();
        assertThat(shipped.match("« Rave + L’Âge d’or » de CCN – Ballet de Lorraine", List.of()).matched()).isFalse();
        assertThat(shipped.match("Nocturne (Parade)", List.of()).community()).isFalse();
        // and the real ones still count
        assertThat(shipped.match("Concert néo soul", List.of()).genres()).containsExactly("jazz & acoustic");
        assertThat(shipped.match("Sunday Tribute - Pop Punk", List.of()).genres()).contains("rock & alternative");
        assertThat(shipped.match("Défilé des allumoirs", List.of()).community()).isTrue();
    }

    @Test
    void bucketsMustEqualBankBuckets() {
        rejects(VALID.replace("  \"jazz & acoustic\": [jazz, jazz manouche, techno]\n", ""), "jazz & acoustic");
        rejects(VALID.replace("exclude_phrases:", "  \"polka\": [polka]\nexclude_phrases:"), "polka");
    }

    @Test
    void duplicateKeyRejected() {
        rejects(VALID.replace("exclude_phrases:", "  \"house & techno\": [rave]\nexclude_phrases:"), "invalid YAML");
    }

    @Test
    void unknownRootKeyRejected() {
        rejects(VALID + "stop_words: [the]\n", "unknown key 'stop_words'");
    }

    @Test
    void nonAsciiKeywordRejected() {
        rejects(VALID.replace("[techno, house, deep house]", "[techno, house, фанк]"), "ASCII");
        rejects(VALID.replace("[techno, house, deep house]", "[techno, House]"), "lowercase");
        rejects(VALID.replace("[techno, house, deep house]", "[techno, h]"), "2-64");
        rejects(VALID.replace("[techno, house, deep house]", "[techno, \"" + "a".repeat(65) + "\"]"), "2-64");
    }

    @Test
    void duplicateKeywordInListRejected() {
        rejects(VALID.replace("[techno, house, deep house]", "[techno, house, techno]"), "duplicate");
        rejects(VALID.replace("[carnaval, fete de la musique, braderie]", "[carnaval, carnaval]"), "duplicate");
    }

    @Test
    void missingOrBadVersionRejected() {
        rejects(VALID.replace("version: 1\n", ""), "version");
        rejects(VALID.replace("version: 1", "version: 0"), "version");
    }

    @Test
    void keywordInTwoBucketsAllowed() {
        assertThat(matcher.buckets().get("jazz & acoustic")).contains("techno");
    }

    @Test
    void wholeWordOnly() {
        assertThat(matcher.match("Housewarming party", List.of()).matched()).isFalse();
        assertThat(matcher.match("Technotronic", List.of()).matched()).isFalse();
        assertThat(matcher.match("House night", List.of()).genres()).containsExactly("house & techno");
    }

    @Test
    void accentInsensitive() {
        Match m = matcher.match("Fête de la Musique 2026", List.of());

        assertThat(m.community()).isTrue();
        assertThat(m.genres()).isEmpty();
        assertThat(GenreMatcher.normalise("  Fête  de la-Musique!")).isEqualTo("fete de la musique");
    }

    @Test
    void excludePhraseWins() {
        assertThat(matcher.match("Open House techno", List.of()).genres()).isEmpty();
        assertThat(matcher.match("Soirée", List.of("Portes ouvertes", "techno")).genres()).isEmpty();
    }

    @Test
    void twoBucketsBothReturned() {
        assertThat(matcher.match("Techno au parc", List.of()).genres())
                .containsExactly("house & techno", "jazz & acoustic");
        assertThat(matcher.match("Soirée", List.of("jazz manouche", "deep house")).genres())
                .containsExactly("house & techno", "jazz & acoustic");
    }

    @Test
    void phraseNeverSpansTitleAndKeyword() {
        // "deep" ends the title and "house" starts no keyword: no "deep house" across the boundary
        assertThat(matcher.match("Plongée deep", List.of("housing")).matched()).isFalse();
    }

    @Test
    void unmatchedGenreIgnored() {
        Match m = matcher.match("Conférence sur la lumière", List.of("histoire"));

        assertThat(m.genres()).isEmpty();
        assertThat(m.community()).isFalse();
        assertThat(m.matched()).isFalse();
    }
}
