package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Citation;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.StoredGroup;
import com.imin.iminapi.audienceplan.service.PortraitResearchStore.WebSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Recorded extraction answers against the code rules; no model is called. */
class PortraitResearchParserTest {

    private static final Citation BAM = new Citation("https://www.bam-metz.fr/programme", "BAM Metz");
    private static final Citation TRINI = new Citation("https://trinitaires.fr/agenda/", "Les Trinitaires");
    private static final List<Citation> CITED = List.of(BAM, TRINI);

    private final PortraitResearchParser parser = new PortraitResearchParser(new IdentityLabelGuard(List.of()));

    private static Map<String, String> towns() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("Metz", "metz");
        t.put("metz", "metz");
        t.put("Nancy", "nancy");
        t.put("nancy", "nancy");
        return t;
    }

    private List<StoredGroup> parse(String groupsJson) {
        return parser.parse("{\"groups\":[" + groupsJson + "]}", CITED, towns());
    }

    @Test
    void everyUrlFromThisRunsResults_isCited_andKeepsItsTitle() {
        List<StoredGroup> g = parse("""
                {"label":"Techno regulars of Metz","description":"They follow the BAM club nights.",
                 "basis":"regulars","towns":["Metz"],"sourceUrls":["https://www.bam-metz.fr/programme"]}""");

        assertThat(g).containsExactly(new StoredGroup("Techno regulars of Metz", "They follow the BAM club nights.",
                "regulars", List.of("metz"), List.of(new WebSource(BAM.url(), "BAM Metz")), "cited"));
    }

    @Test
    void aUrlNotInTheResults_makesTheGroupAssumed_andIsDropped() {
        StoredGroup g = parse("""
                {"label":"Techno regulars","basis":"regulars","towns":[],
                 "sourceUrls":["https://www.bam-metz.fr/programme","https://invented.example/techno"]}""").get(0);

        assertThat(g.confidence()).isEqualTo("assumed");
        assertThat(g.sources()).extracting(WebSource::url).containsExactly(BAM.url());
    }

    @Test
    void noUrlAtAll_isAssumed() {
        StoredGroup g = parse("""
                {"label":"Techno regulars","basis":"regulars","towns":[],"sourceUrls":[]}""").get(0);

        assertThat(g.confidence()).isEqualTo("assumed");
        assertThat(g.sources()).isEmpty();
    }

    @Test
    void urlMatching_ignoresCaseOfHost_fragmentAndTrailingSlash_butNotThePath() {
        assertThat(parse("""
                {"label":"A","basis":"none","sourceUrls":["https://TRINITAIRES.fr/agenda#june"]}""").get(0).confidence())
                .isEqualTo("cited");
        assertThat(parse("""
                {"label":"B","basis":"none","sourceUrls":["https://trinitaires.fr/other"]}""").get(0).confidence())
                .isEqualTo("assumed");
        assertThat(parse("""
                {"label":"C","basis":"none","sourceUrls":["javascript:alert(1)"]}""").get(0).confidence())
                .isEqualTo("assumed");
    }

    @Test
    void modelSizes_areNeverStored_andNumbersInTextAreDropped() {
        List<StoredGroup> g = parse("""
                {"label":"Students of Nancy","description":"Around 30,000 students.","basis":"students",
                 "size":{"low":30000,"high":31000},"sourceUrls":[]},
                {"label":"Top 10 ravers","description":"x","basis":"regulars","sourceUrls":[]}""");

        assertThat(g).hasSize(1);
        assertThat(g.get(0).description()).isNull();
        assertThat(PortraitResearchStore.write(g)).doesNotContain("30000").doesNotContain("31000");
    }

    @Test
    void identityLabels_dropTheGroup_inLabelOrDescription() {
        List<StoredGroup> g = parse("""
                {"label":"Muslim students","basis":"students","sourceUrls":[]},
                {"label":"Night owls","description":"Mostly the queer scene.","basis":"none","sourceUrls":[]},
                {"label":"Vinyl diggers","description":"Record shop regulars.","basis":"none","sourceUrls":[]}""");

        assertThat(g).extracting(StoredGroup::label).containsExactly("Vinyl diggers");
    }

    @Test
    void unknownBasis_isNone_andTownsOutsideTheListAreDropped() {
        StoredGroup g = parse("""
                {"label":"Weekend crowd","basis":"everyone","towns":["Nancy","Luxembourg"," NANCY "],"sourceUrls":[]}""")
                .get(0);

        assertThat(g.basis()).isEqualTo("none");
        assertThat(g.towns()).containsExactly("nancy");
    }

    @Test
    void atMostFourGroups_andOverlongTextIsRefused() {
        String one = "{\"label\":\"G\",\"basis\":\"none\",\"sourceUrls\":[]}";
        assertThat(parse(String.join(",", one, one, one, one, one))).hasSize(4);

        assertThat(parse("{\"label\":\"" + "a".repeat(121) + "\",\"basis\":\"none\"}")).isEmpty();
        assertThat(parse("{\"label\":\"G\",\"description\":\"" + "a".repeat(301) + "\"}").get(0).description())
                .isNull();
    }

    @Test
    void refusalProseEmptyOrMalformedAnswers_giveNoGroups() {
        assertThat(parser.parse("I can't help with that.", CITED, towns())).isEmpty();
        assertThat(parser.parse("", CITED, towns())).isEmpty();
        assertThat(parser.parse(null, CITED, towns())).isEmpty();
        assertThat(parser.parse("{\"groups\": \"none\"}", CITED, towns())).isEmpty();
        assertThat(parser.parse("{\"groups\":[{\"label\":", CITED, towns())).isEmpty();
        assertThat(parser.parse("{\"groups\":[{\"description\":\"no label\"}]}", CITED, towns())).isEmpty();
    }

    @Test
    void aSourceTitleThatLabelsPeople_fallsBackToTheHost_aBlankOneToo() {
        List<Citation> cited = List.of(new Citation("https://www.bam-metz.fr/programme", "Muslim youth nights at BAM"),
                new Citation("https://trinitaires.fr/agenda/", "  "));

        StoredGroup g = parser.parse("""
                {"groups":[{"label":"Techno regulars","basis":"regulars","sourceUrls":
                  ["https://www.bam-metz.fr/programme","https://trinitaires.fr/agenda/"]}]}""", cited, towns()).get(0);

        assertThat(g.sources()).containsExactly(new WebSource("https://www.bam-metz.fr/programme", "www.bam-metz.fr"),
                new WebSource("https://trinitaires.fr/agenda/", "trinitaires.fr"));
        assertThat(g.confidence()).isEqualTo("cited");
    }

    @Test
    void jsonWrappedInProse_isRead() {
        List<StoredGroup> g = parser.parse("Here you go:\n{\"groups\":[{\"label\":\"Vinyl diggers\",\"basis\":\"none\"}]}\n",
                CITED, towns());

        assertThat(g).extracting(StoredGroup::label).containsExactly("Vinyl diggers");
    }
}
