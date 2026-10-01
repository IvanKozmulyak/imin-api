package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.MonthViews;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class WikimediaPageviewsWriterTest {

    private static final Instant T1 = Instant.parse("2026-09-28T03:15:00Z");
    private static final Instant T2 = Instant.parse("2026-10-05T03:15:00Z");

    @Autowired WikimediaPageviewsWriter writer;
    @Autowired WikimediaPageviewMonthRepository repository;
    @Autowired EntityManager em;

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
        int written = writer.upsert("fr.wikipedia", "Techno", List.of(mv(2026, 8, 10), mv(2026, 9, 12)), T1);

        assertThat(written).isEqualTo(2);
        assertThat(stored("fr.wikipedia", "Techno"))
                .extracting(WikimediaPageviewMonth::getViewMonth, WikimediaPageviewMonth::getViews,
                        WikimediaPageviewMonth::getSyncedAt)
                .containsExactly(tuple(LocalDate.of(2026, 9, 1), 12L, T1), tuple(LocalDate.of(2026, 8, 1), 10L, T1));
    }

    @Test
    void updatesExistingMonthAndSyncedAt() {
        writer.upsert("fr.wikipedia", "Techno", List.of(mv(2026, 8, 10), mv(2026, 9, 12)), T1);
        stored("fr.wikipedia", "Techno");

        writer.upsert("fr.wikipedia", "Techno", List.of(mv(2026, 9, 15), mv(2026, 10, 3)), T2);

        assertThat(stored("fr.wikipedia", "Techno"))
                .extracting(WikimediaPageviewMonth::getViewMonth, WikimediaPageviewMonth::getViews,
                        WikimediaPageviewMonth::getSyncedAt)
                .containsExactly(tuple(LocalDate.of(2026, 10, 1), 3L, T2), tuple(LocalDate.of(2026, 9, 1), 15L, T2),
                        tuple(LocalDate.of(2026, 8, 1), 10L, T1));
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void otherArticleRowsUntouched() {
        writer.upsert("de.wikipedia", "Techno", List.of(mv(2026, 9, 99)), T1);
        writer.upsert("fr.wikipedia", "House_music", List.of(mv(2026, 9, 7)), T1);

        writer.upsert("fr.wikipedia", "Techno", List.of(mv(2026, 9, 12)), T2);

        assertThat(stored("de.wikipedia", "Techno")).extracting(WikimediaPageviewMonth::getViews,
                WikimediaPageviewMonth::getSyncedAt).containsExactly(tuple(99L, T1));
        assertThat(stored("fr.wikipedia", "House_music")).extracting(WikimediaPageviewMonth::getViews,
                WikimediaPageviewMonth::getSyncedAt).containsExactly(tuple(7L, T1));
        assertThat(stored("fr.wikipedia", "Techno")).extracting(WikimediaPageviewMonth::getViews)
                .containsExactly(12L);
    }

    @Test
    void negativeViewsRejectedByCheck() {
        writer.upsert("fr.wikipedia", "Techno", List.of(mv(2026, 9, -1)), T1);

        Throwable thrown = catchThrowable(() -> em.flush());

        assertThat(thrown).isNotNull();
        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root.getMessage().toLowerCase(Locale.ROOT)).contains("ck_wikimedia_pageviews_month_views");
    }
}
