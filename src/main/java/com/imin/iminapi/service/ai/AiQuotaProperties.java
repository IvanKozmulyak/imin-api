package com.imin.iminapi.service.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-user rolling-24h AI poster-generation ceiling (anti-abuse). */
@ConfigurationProperties(prefix = "imin.ai-quota")
public class AiQuotaProperties {
    /** Max image-pipeline attempts (poster concept + regenerate) per user per rolling 24h. */
    private int imagePerDay = 3;

    /**
     * Max predictor Stage-0 scoring LLM runs (kind=score) per user per rolling 24h. Generous:
     * the input-hash cache means an organizer only burns quota on a materially changed draft
     * or a throttled manual refresh — real usage should be a handful a day.
     */
    private int scorePerDay = 50;

    public int getImagePerDay() { return imagePerDay; }
    public void setImagePerDay(int imagePerDay) { this.imagePerDay = imagePerDay; }

    public int getScorePerDay() { return scorePerDay; }
    public void setScorePerDay(int scorePerDay) { this.scorePerDay = scorePerDay; }
}
