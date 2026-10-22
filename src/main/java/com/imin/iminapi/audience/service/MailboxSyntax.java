package com.imin.iminapi.audience.service;

import java.util.Locale;
import java.util.Set;

/**
 * Whether an address can be mailed at all: one {@code local@domain} mailbox with no whitespace or control
 * characters, inside the RFC 5321 length limits, on a domain that is not reserved for documentation or
 * testing (example.com/.net/.org and their subdomains, the .test/.example/.invalid/.localhost/.local TLDs).
 * Non-ASCII letters are accepted; the provider decides on those.
 */
public final class MailboxSyntax {

    private static final int MAX_ADDRESS = 254;
    private static final int MAX_LOCAL = 64;
    private static final int MAX_DOMAIN = 253;
    private static final int MAX_LABEL = 63;
    private static final Set<String> RESERVED_DOMAINS = Set.of("example.com", "example.net", "example.org");
    private static final Set<String> RESERVED_TLDS = Set.of("test", "example", "invalid", "localhost", "local");
    private static final String LOCAL_SPECIALS = "!#$%&'*+-/=?^_`{|}~.";

    private MailboxSyntax() {}

    public static boolean isDeliverable(String address) {
        if (address == null || address.isEmpty() || address.length() > MAX_ADDRESS) return false;
        for (int i = 0; i < address.length(); i++) {
            char ch = address.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || Character.isSpaceChar(ch)) return false;
        }
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) return false;
        String local = address.substring(0, at);
        String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
        return validLocal(local) && validDomain(domain) && !reserved(domain);
    }

    private static boolean validLocal(String local) {
        if (local.length() > MAX_LOCAL || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            char ch = local.charAt(i);
            if (!Character.isLetterOrDigit(ch) && LOCAL_SPECIALS.indexOf(ch) < 0) return false;
        }
        return true;
    }

    private static boolean validDomain(String domain) {
        if (domain.length() > MAX_DOMAIN) return false;
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char ch = label.charAt(i);
                if (!Character.isLetterOrDigit(ch) && ch != '-') return false;
            }
        }
        // A numeric top-level label is an IP literal, never a mail domain.
        return !labels[labels.length - 1].chars().allMatch(Character::isDigit);
    }

    private static boolean reserved(String domain) {
        String tld = domain.substring(domain.lastIndexOf('.') + 1);
        if (RESERVED_TLDS.contains(tld)) return true;
        for (String r : RESERVED_DOMAINS) {
            if (domain.equals(r) || domain.endsWith("." + r)) return true;
        }
        return false;
    }
}
