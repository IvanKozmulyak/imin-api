package com.imin.iminapi.service.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-user rolling-24h AI poster-generation ceiling (anti-abuse). */
@ConfigurationProperties(prefix = "imin.ai-quota")
public class AiQuotaProperties {
    /** Max image-pipeline attempts (poster concept + regenerate) per user per rolling 24h. */
    private int imagePerDay = 3;

    public int getImagePerDay() { return imagePerDay; }
    public void setImagePerDay(int imagePerDay) { this.imagePerDay = imagePerDay; }
}
