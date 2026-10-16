package com.imin.iminapi.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Who the imin team inboxes are ({@code imin.team-alerts.*}). Read per call; an empty list switches the alert off. */
@ConfigurationProperties(prefix = "imin.team-alerts")
public class TeamAlertProperties {

    // One address: a single @ with text on both sides, no whitespace and no ';' (a pasted list or "Name <addr>").
    private static final Pattern ADDRESS = Pattern.compile("[^@\\s;]+@[^@\\s;]+", Pattern.UNICODE_CHARACTER_CLASS);

    private List<String> emails = List.of();

    public List<String> getEmails() { return emails; }

    /** Blank entries (a stray or trailing comma) are dropped; an entry that is not one address fails startup. */
    public void setEmails(List<String> emails) {
        List<String> clean = emails == null ? List.of() : emails.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(e -> !e.isEmpty())
                .toList();
        for (String entry : clean) {
            if (!ADDRESS.matcher(entry).matches()) {
                throw new IllegalArgumentException("imin.team-alerts.emails entry is not one email address: " + entry);
            }
        }
        this.emails = clean;
    }
}
