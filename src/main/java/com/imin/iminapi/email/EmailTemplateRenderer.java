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
        String html = renderOne(templateName, "html", values);
        String text = renderOne(templateName, "txt", values);
        return new Rendered(html, text);
    }

    private String renderOne(String templateName, String ext, Map<String, String> values) {
        String resource = "email-templates/" + templateName + "." + ext;
        String raw = readClasspath(resource);
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
}
