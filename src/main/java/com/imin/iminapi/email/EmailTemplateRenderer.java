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
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
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
