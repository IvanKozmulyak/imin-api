package com.imin.iminapi.predictor.sources.wikimedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Monthly user pageviews of one Wikipedia article from the Wikimedia Analytics API (CC0 1.0).
 * Keeps only items matching every field it asked for; never invents a month that is not published yet.
 */
public class WikimediaPageviewsClient {

    private static final Logger log = LoggerFactory.getLogger(WikimediaPageviewsClient.class);
    static final String BASE_URL = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MONTH_START = Pattern.compile("^\\d{6}0100$");
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    public record MonthViews(YearMonth month, long views) {}

    /** {@code missing} = upstream has no data for the article (404). */
    public record Result(boolean missing, List<MonthViews> months) {
        public Result {
            months = months == null ? List.of() : List.copyOf(months);
        }

        public static Result notFound() {
            return new Result(true, List.of());
        }
    }

    /** Upstream answered 429: the caller stops its run rather than keep hammering. */
    public static class WikimediaRateLimitedException extends RuntimeException {
        public WikimediaRateLimitedException(String message) {
            super(message);
        }
    }

    private final RestClient http;

    public WikimediaPageviewsClient(RestClient.Builder builder, WikimediaProperties props) {
        String ua = props.getUserAgent();
        if (ua == null || (!ua.contains("@") && !ua.contains("https://"))) {
            throw new IllegalStateException("Wikimedia User-Agent must carry a contact (an email or https URL): " + ua);
        }
        this.http = builder.defaultHeader("User-Agent", ua).build();
        log.info("Wikimedia pageviews client User-Agent: {}", ua);
    }

    /** Spaces become underscores; the title is one path segment, so '/' and '&' are escaped too. */
    public static String encodeTitle(String title) {
        return URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
    }

    public static URI uri(String project, String article, YearMonth from, YearMonth to) {
        return URI.create(BASE_URL + project + "/all-access/user/" + encodeTitle(article) + "/monthly/"
                + from.format(YYYYMM) + "0100/" + to.format(YYYYMM) + String.format("%02d", to.lengthOfMonth()) + "00");
    }

    public Result fetch(String project, String article, YearMonth from, YearMonth to) {
        URI uri = uri(project, article, from, to);
        return http.get().uri(uri).exchange((req, res) -> {
            HttpStatusCode status = res.getStatusCode();
            if (status.value() == HttpStatus.NOT_FOUND.value()) return Result.notFound();
            if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new WikimediaRateLimitedException("Wikimedia pageviews 429 for " + project + "/" + article);
            }
            if (!status.is2xxSuccessful()) {
                throw new IllegalStateException("Wikimedia pageviews " + status.value() + " for " + project + "/" + article);
            }
            JsonNode root;
            try {
                root = JSON.readTree(res.getBody());
            } catch (IOException e) {
                throw new IllegalStateException("Wikimedia pageviews: body is not JSON for " + project + "/" + article, e);
            }
            if (root == null || !root.path("items").isArray()) {
                throw new IllegalStateException("Wikimedia pageviews: no items array for " + project + "/" + article);
            }
            return new Result(false, months(root.path("items"), project, article.replace(' ', '_'), from, to));
        }, true);
    }

    /** Kept items by month, then 0 for each month in [from, latest kept] the answer omitted (it drops zero months). */
    static List<MonthViews> months(JsonNode items, String project, String article, YearMonth from, YearMonth to) {
        TreeMap<YearMonth, Long> kept = new TreeMap<>();
        for (JsonNode it : items) {
            if (!"user".equals(it.path("agent").asText(null))
                    || !"all-access".equals(it.path("access").asText(null))
                    || !"monthly".equals(it.path("granularity").asText(null))
                    || !project.equals(it.path("project").asText(null))
                    || !article.equals(it.path("article").asText(null))) continue;
            String ts = it.path("timestamp").asText("");
            if (!MONTH_START.matcher(ts).matches()) continue;
            YearMonth month;
            try {
                month = YearMonth.parse(ts.substring(0, 6), YYYYMM);
            } catch (Exception e) {
                continue;
            }
            if (month.isBefore(from) || month.isAfter(to)) continue;
            JsonNode views = it.path("views");
            if (!views.isIntegralNumber() || !views.canConvertToLong() || views.asLong() < 0) continue;
            kept.putIfAbsent(month, views.asLong());
        }
        List<MonthViews> out = new ArrayList<>();
        if (kept.isEmpty()) return out;
        YearMonth latest = kept.lastKey();
        for (YearMonth m = from; !m.isAfter(latest); m = m.plusMonths(1)) {
            out.add(new MonthViews(m, kept.getOrDefault(m, 0L)));
        }
        return out;
    }
}
