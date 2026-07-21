package com.imin.iminapi.email;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EmailTemplateRenderer {

    public record Rendered(String html, String text) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

    public Rendered render(String templateName, Map<String, String> values) {
        return render(templateName, null, values);
    }

    /**
     * Locale-aware render with bulletproof EN fallback.
     *
     * <p>{@code locale} is normalized (trim + lowercase) to one of
     * {@code es}/{@code fr}/{@code uk}; anything else (including {@code null},
     * blank, or {@code en}) uses the base English templates with no suffix.
     * For each extension independently: when a supported locale suffix applies
     * and {@code email-templates/<name>.<locale>.<ext>} exists on the classpath,
     * it is used; otherwise we fall back to the base {@code <name>.<ext>}. A
     * missing localized variant NEVER throws — only a missing BASE template does
     * (unchanged from the 2-arg behavior). Placeholder substitution and HTML
     * escaping are identical to the 2-arg path.
     */
    public Rendered render(String templateName, String locale, Map<String, String> values) {
        String html = renderOne(templateName, "html", locale, values);
        String text = renderOne(templateName, "txt", locale, values);
        return new Rendered(html, text);
    }

    private String renderOne(String templateName, String ext, String locale, Map<String, String> values) {
        String norm = EmailLocale.normalize(locale);
        String resource = "email-templates/" + templateName + "." + ext;
        String raw = null;
        if (!"en".equals(norm)) {
            String localized = "email-templates/" + templateName + "." + norm + "." + ext;
            // Only read the localized variant when it is actually on the classpath;
            // a missing variant must fall back to the base EN file, never throw.
            if (classpathExists(localized)) {
                raw = readClasspathOrNull(localized);
                if (raw != null) {
                    resource = localized;
                }
            }
        }
        if (raw == null) {
            // Base template: readClasspath still throws if EN is missing (as today).
            raw = readClasspath(resource);
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new IllegalStateException(
                        "Missing value for placeholder '" + key + "' in template " + resource);
            }
            String substituted = "html".equals(ext) ? htmlEscape(value) : value;
            m.appendReplacement(out, Matcher.quoteReplacement(substituted));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String htmlEscape(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private String readClasspath(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Email template not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template: " + resource, e);
        }
    }

    private boolean classpathExists(String resource) {
        return getClass().getClassLoader().getResource(resource) != null;
    }

    /** Reads a classpath resource, returning {@code null} if it is absent or unreadable — never throws. */
    private String readClasspathOrNull(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
