package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.MonthViews;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Upserts one article's months; rows of other articles and months not in the answer are left alone. */
@Component
public class WikimediaPageviewsWriter {

    private final WikimediaPageviewMonthRepository repository;

    public WikimediaPageviewsWriter(WikimediaPageviewMonthRepository repository) {
        this.repository = repository;
    }

    /** Updates views and synced_at of stored months and inserts the rest (no ON CONFLICT, so H2 runs it too). */
    @Transactional
    public int upsert(String project, String article, List<MonthViews> months, Instant syncedAt) {
        if (months.isEmpty()) return 0;
        List<LocalDate> days = months.stream().map(m -> m.month().atDay(1)).toList();
        Map<LocalDate, WikimediaPageviewMonth> stored = new HashMap<>();
        for (WikimediaPageviewMonth row : repository.findByProjectAndArticleAndViewMonthIn(project, article, days)) {
            stored.put(row.getViewMonth(), row);
        }
        List<WikimediaPageviewMonth> toSave = new ArrayList<>();
        for (MonthViews m : months) {
            LocalDate day = m.month().atDay(1);
            WikimediaPageviewMonth row = stored.remove(day);
            if (row == null) {
                row = new WikimediaPageviewMonth();
                row.setProject(project);
                row.setArticle(article);
                row.setViewMonth(day);
            }
            row.setViews(m.views());
            row.setSyncedAt(syncedAt);
            toSave.add(row);
        }
        repository.saveAll(toSave);
        return toSave.size();
    }
}
