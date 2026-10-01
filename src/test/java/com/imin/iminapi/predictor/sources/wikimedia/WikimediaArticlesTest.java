package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaArticles.Article;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikimediaArticlesTest {

    private static final QuestionBank BANK = QuestionBankLoader.load(new DefaultResourceLoader());

    private static final String VALID = """
            version: 1
            verified_on: 2026-10-01
            languages: { FR: fr, DE: de }
            buckets:
              "house & techno":
                sub_genres:
                  techno: { fr: Techno, de: Techno }
                  house: { fr: House music }
              "pop":
                article: { fr: Pop (musique), de: Popmusik }
                sub_genres:
                  k-pop: { fr: K-pop }
            """;

    private static WikimediaArticles parse(String yaml) {
        return WikimediaArticles.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), BANK);
    }

    private static void rejected(String yaml, String... parts) {
        var assertion = assertThatThrownBy(() -> parse(yaml)).isInstanceOf(IllegalStateException.class);
        for (String p : parts) assertion.hasMessageContaining(p);
    }

    @Test
    void shippedFileLoads() {
        WikimediaArticles shipped = WikimediaArticles.load(new DefaultResourceLoader(), BANK);

        assertThat(shipped.version()).isGreaterThanOrEqualTo(1);
        assertThat(shipped.verifiedOn()).isNotNull();
        assertThat(shipped.distinctArticles()).isNotEmpty()
                .allSatisfy(a -> {
                    assertThat(a.title().length()).isLessThanOrEqualTo(WikimediaArticles.MAX_TITLE);
                    assertThat(a.title()).doesNotContain(" ");
                    assertThat(a.project()).endsWith(".wikipedia");
                });
        Question q91 = BANK.questions().stream().filter(q -> q.id().equals("9.1")).findFirst().orElseThrow();
        for (String country : q91.countries()) {
            assertThat(shipped.language(country)).as(country).isPresent();
        }
    }

    @Test
    void inlineValidFileLoads() {
        WikimediaArticles a = parse(VALID);

        assertThat(a.version()).isEqualTo(1);
        assertThat(a.verifiedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(a.language("FR")).contains("fr");
        assertThat(a.language("NL")).isEmpty();
    }

    @Test
    void duplicateKeyRejected() {
        rejected(VALID.replace("house: { fr: House music }", "house: { fr: House music }\n      house: { fr: X }"),
                "duplicate");
    }

    @Test
    void unknownRootKeyRejected() {
        rejected(VALID + "extra: 1\n", "unknown key 'extra'");
    }

    @Test
    void unknownBucketKeyRejected() {
        rejected(VALID.replace("    article: { fr: Pop (musique), de: Popmusik }",
                "    articles: { fr: Pop (musique) }"), "pop", "unknown key 'articles'");
    }

    @Test
    void unknownSubGenreKeyLevelRejected() {
        rejected(VALID.replace("techno: { fr: Techno, de: Techno }", "techno: { fr: Techno, title: Techno }"),
                "techno", "unknown key 'title'");
    }

    @Test
    void unknownBucketRejected() {
        rejected(VALID.replace("\"pop\":", "\"polka\":"), "unknown bucket 'polka'");
    }

    @Test
    void subGenreNotInProfileRejected() {
        rejected(VALID.replace("k-pop: { fr: K-pop }", "techno: { fr: Techno }"), "pop", "techno");
    }

    @Test
    void titleLanguageNotConfiguredRejected() {
        rejected(VALID.replace("k-pop: { fr: K-pop }", "k-pop: { fr: K-pop, es: K-pop }"),
                "k-pop", "es", "not configured");
    }

    @Test
    void unsupportedLanguageRejected() {
        rejected(VALID.replace("languages: { FR: fr, DE: de }", "languages: { FR: fr, DE: de, IT: it }"),
                "languages", "it");
    }

    @Test
    void badCountryCodeRejected() {
        rejected(VALID.replace("languages: { FR: fr, DE: de }", "languages: { FR: fr, de: de }"),
                "languages", "de");
    }

    @Test
    void blankTitleRejected() {
        rejected(VALID.replace("k-pop: { fr: K-pop }", "k-pop: { fr: \"  \" }"), "k-pop", "blank");
    }

    @Test
    void overlongTitleRejected() {
        String longTitle = "a".repeat(WikimediaArticles.MAX_TITLE - 1) + " b";
        rejected(VALID.replace("k-pop: { fr: K-pop }", "k-pop: { fr: " + longTitle + " }"), "k-pop", "255");
        // exactly 255 after space -> underscore still loads
        String edge = "a".repeat(WikimediaArticles.MAX_TITLE - 2) + " b";
        assertThat(parse(VALID.replace("k-pop: { fr: K-pop }", "k-pop: { fr: " + edge + " }"))
                .lookup("FR", "pop", "k-pop")).map(Article::title).contains("a".repeat(253) + "_b");
    }

    @Test
    void missingVersionRejected() {
        rejected(VALID.replace("version: 1\n", ""), "version");
        rejected(VALID.replace("version: 1", "version: 0"), "version");
    }

    @Test
    void badVerifiedOnRejected() {
        rejected(VALID.replace("verified_on: 2026-10-01", "verified_on: soon"), "verified_on");
    }

    @Test
    void spacesBecomeUnderscores() {
        assertThat(parse(VALID).lookup("FR", "house & techno", "house"))
                .contains(new Article("fr.wikipedia", "House_music"));
    }

    @Test
    void lookupPrefersSubGenreThenBucket() {
        WikimediaArticles a = parse(VALID);

        assertThat(a.lookup("FR", "pop", "k-pop")).contains(new Article("fr.wikipedia", "K-pop"));
        // DE has no k-pop title, so the bucket article answers
        assertThat(a.lookup("DE", "pop", "k-pop")).contains(new Article("de.wikipedia", "Popmusik"));
        assertThat(a.lookup("FR", "pop", null)).contains(new Article("fr.wikipedia", "Pop_(musique)"));
        // no bucket article and no sub-genre title
        assertThat(a.lookup("DE", "house & techno", "house")).isEmpty();
        assertThat(a.lookup("DE", "house & techno", null)).isEmpty();
        // unconfigured country, unknown bucket
        assertThat(a.lookup("NL", "pop", null)).isEmpty();
        assertThat(a.lookup("FR", "jazz & acoustic", "jazz")).isEqualTo(Optional.empty());
    }

    @Test
    void distinctArticlesListsEachOnce() {
        String yaml = VALID.replace("house: { fr: House music }", "house: { fr: Techno }");

        assertThat(parse(yaml).distinctArticles()).containsExactly(
                new Article("fr.wikipedia", "Techno"),
                new Article("de.wikipedia", "Techno"),
                new Article("fr.wikipedia", "Pop_(musique)"),
                new Article("de.wikipedia", "Popmusik"),
                new Article("fr.wikipedia", "K-pop"));
    }

    @Test
    void articleUrlIsTheEncodedWikiPage() {
        assertThat(new Article("fr.wikipedia", "Pop_(musique)").url())
                .isEqualTo("https://fr.wikipedia.org/wiki/Pop_%28musique%29");
        assertThat(new Article("uk.wikipedia", "Техно").url())
                .isEqualTo("https://uk.wikipedia.org/wiki/%D0%A2%D0%B5%D1%85%D0%BD%D0%BE");
    }
}
