package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.MonthViews;
import com.imin.iminapi.support.IminIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@IminIntegrationTest
@Transactional
class WikimediaPageviewsWriterTest {

    private static final Instant T1 = Instant.parse("2026-09-28T03:15:00Z");
    private static final Instant T2 = Instant.parse("2026-10-05T03:15:00Z");

    @Autowired WikimediaPageviewsWriter writer;
    @Autowired WikimediaPageviewMonthRepository repository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    /** Article names carry random letters, so committed rows of the shared table never match. */
    private final String letters = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String techno = "Techno_" + letters;
    private final String house = "House_music_" + letters;

    private static MonthViews mv(int year, int month, long views) {
        return new MonthViews(YearMonth.of(year, month), views);
    }

    private List<WikimediaPageviewMonth> stored(String project, String article) {
        em.flush();
        em.clear();
        return repository.findTop24ByProjectAndArticleOrderByViewMonthDesc(project, article);
    }

    @Test
    void insertsNewMonths() {
        int written = writer.upsert("fr.wikipedia", techno, List.of(mv(2026, 8, 10), mv(2026, 9, 12)), T1);

        assertThat(written).isEqualTo(2);
        assertThat(stored("fr.wikipedia", techno))
                .extracting(WikimediaPageviewMonth::getViewMonth, WikimediaPageviewMonth::getViews,
                        WikimediaPageviewMonth::getSyncedAt)
                .containsExactly(tuple(LocalDate.of(2026, 9, 1), 12L, T1), tuple(LocalDate.of(2026, 8, 1), 10L, T1));
    }

    @Test
    void updatesExistingMonthAndSyncedAt() {
        writer.upsert("fr.wikipedia", techno, List.of(mv(2026, 8, 10), mv(2026, 9, 12)), T1);
        stored("fr.wikipedia", techno);

        writer.upsert("fr.wikipedia", techno, List.of(mv(2026, 9, 15), mv(2026, 10, 3)), T2);

        assertThat(stored("fr.wikipedia", techno))
                .extracting(WikimediaPageviewMonth::getViewMonth, WikimediaPageviewMonth::getViews,
                        WikimediaPageviewMonth::getSyncedAt)
                .containsExactly(tuple(LocalDate.of(2026, 10, 1), 3L, T2), tuple(LocalDate.of(2026, 9, 1), 15L, T2),
                        tuple(LocalDate.of(2026, 8, 1), 10L, T1));
        assertThat(jdbc.queryForObject("select count(*) from wikimedia_pageviews_month where article like ?",
                Long.class, "%_" + letters)).isEqualTo(3L);
    }

    @Test
    void otherArticleRowsUntouched() {
        writer.upsert("de.wikipedia", techno, List.of(mv(2026, 9, 99)), T1);
        writer.upsert("fr.wikipedia", house, List.of(mv(2026, 9, 7)), T1);

        writer.upsert("fr.wikipedia", techno, List.of(mv(2026, 9, 12)), T2);

        assertThat(stored("de.wikipedia", techno)).extracting(WikimediaPageviewMonth::getViews,
                WikimediaPageviewMonth::getSyncedAt).containsExactly(tuple(99L, T1));
        assertThat(stored("fr.wikipedia", house)).extracting(WikimediaPageviewMonth::getViews,
                WikimediaPageviewMonth::getSyncedAt).containsExactly(tuple(7L, T1));
        assertThat(stored("fr.wikipedia", techno)).extracting(WikimediaPageviewMonth::getViews)
                .containsExactly(12L);
    }
}
