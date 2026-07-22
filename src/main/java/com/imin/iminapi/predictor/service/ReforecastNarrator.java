package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.ProjectionBand;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Narrates a COMPUTED re-forecast (spec §4.2 / §7.1: "the LLM narrates and explains" — it never
 * does the math). One short, honest sentence explaining the pacing story, generated ONLY when
 * the projection band changed vs the previous re-forecast (spec §7.3: "LLM narration regenerates
 * only when the projection changes band, not per recompute"). The numbers are handed to it
 * already decided; it may not alter them.
 *
 * <p>Behind the platform's @Primary OpenRouter {@link ChatClient}, low temperature. Kept
 * deliberately thin: a plain content call, not a structured one — there is nothing to validate
 * because there are no numbers to guard (those are arithmetic).
 */
@Service
public class ReforecastNarrator {

    /** Prompt semver — stamped on every reforecast ledger row (§7.3). Bump on any prompt change. */
    public static final String PROMPT_VERSION = "1.0.0";

    private static final double TEMPERATURE = 0.3;

    private final ChatClient chat;
    private final PredictorProperties props;
    private final String platformModel;

    public ReforecastNarrator(ChatClient chat, PredictorProperties props,
                              @Value("${openrouter.model}") String platformModel) {
        this.chat = chat;
        this.props = props;
        this.platformModel = platformModel;
    }

    /** The model id used for narration — config override or platform default. Ledger-stamped. */
    public String modelId() {
        String m = props.getModel();
        return (m == null || m.isBlank()) ? platformModel : m;
    }

    /**
     * Everything the narrator is allowed to know — all already computed. The competing-nights and
     * weather fields SEASON the narration only (they never enter the deterministic pacing math);
     * weather is null beyond the forecast horizon and is listed to the model as unknown.
     */
    public record Context(ProjectionBand newBand, ProjectionBand previousBand,
                          int projectedLow, int projectedHigh, int capacity,
                          int currentSold, int comparableEventsCount, String relaxation,
                          boolean sellOutLikely,
                          int competingCount, int competingCapacity, boolean competingGenreOverlap,
                          Integer weatherPrecipPct, Double weatherTempC) {}

    /**
     * One short sentence. Throws on transport failure — the caller swallows it to null so the
     * COMPUTED numbers still render (numbers survive; narration is best-effort).
     */
    public String narrate(Context ctx) {
        String out = chat.prompt()
                .options(OpenAiChatOptions.builder().model(modelId()).temperature(TEMPERATURE).build())
                .user(buildPrompt(ctx))
                .call()
                .content();
        return out == null ? null : out.trim();
    }

    private String buildPrompt(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You narrate a live sales re-forecast for an event organizer. The NUMBERS below are
                already computed by a deterministic engine — you do NOT change them, you explain the
                story in ONE short sentence (max ~20 words).

                HARD RULES:
                - RANGES ONLY. No point estimates, no "will", no "guaranteed", no "sold out" as a fact.
                - Describe the change of story honestly (the projection band moved). Do not invent
                  causes; you only know pacing vs comparable events.
                - No emojis. Plain, calm, advisory tone.
                """);
        sb.append("\nProjection band moved from ").append(ctx.previousBand() == null ? "no prior read" : ctx.previousBand().name())
                .append(" to ").append(ctx.newBand().name()).append(" (").append(ctx.newBand().phrase()).append(").\n");
        sb.append("Projected final sold range: ").append(ctx.projectedLow()).append("–").append(ctx.projectedHigh())
                .append(" of ").append(ctx.capacity()).append(" capacity. Currently sold: ").append(ctx.currentSold()).append(".\n");
        sb.append("Based on ").append(ctx.comparableEventsCount()).append(" comparable completed events (relaxation ")
                .append(ctx.relaxation()).append(").\n");
        if (ctx.sellOutLikely()) sb.append("A sell-out is within the projected range.\n");

        // Optional context signals — real platform + weather data, seasoning only.
        if (ctx.competingCount() > 0) {
            sb.append("Competing events on imin that night in the same city: ").append(ctx.competingCount())
                    .append(" (total advertised capacity ").append(ctx.competingCapacity())
                    .append(ctx.competingGenreOverlap() ? ", including the same genre" : "").append(").\n");
        } else {
            sb.append("No competing imin events that night in the same city.\n");
        }
        if (ctx.weatherPrecipPct() != null || ctx.weatherTempC() != null) {
            sb.append("Weather for the date: ");
            if (ctx.weatherPrecipPct() != null) sb.append(ctx.weatherPrecipPct()).append("% precipitation probability");
            if (ctx.weatherTempC() != null) sb.append(ctx.weatherPrecipPct() != null ? ", " : "")
                    .append("max ").append(Math.round(ctx.weatherTempC())).append("°C");
            sb.append(".\n");
        } else {
            sb.append("Weather: UNKNOWN (beyond the reliable forecast horizon or unavailable) — do not guess it.\n");
        }
        sb.append("\nReturn ONLY the sentence, no preamble.\n");
        return sb.toString();
    }
}
