package com.imin.iminapi.marketing.render;

import com.imin.iminapi.model.Organization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Who a marketing email is from, as printed in its footer: display name, legal name, legal contact. */
public record OrganizerIdentity(String name, String legalName, String legalContact) {

    public static final OrganizerIdentity NONE = new OrganizerIdentity(null, null, null);

    public static OrganizerIdentity of(Organization org) {
        if (org == null) return NONE;
        return new OrganizerIdentity(org.displayName(), org.getLegalName(), org.getLegalContact());
    }

    /** Non-blank parts in footer order; the legal name is dropped when it repeats the display name. */
    public List<String> footerParts() {
        List<String> parts = new ArrayList<>(3);
        String n = clean(name);
        String ln = clean(legalName);
        String lc = clean(legalContact);
        if (n != null) parts.add(n);
        if (ln != null && (n == null || !ln.toLowerCase(Locale.ROOT).equals(n.toLowerCase(Locale.ROOT)))) {
            parts.add(ln);
        }
        if (lc != null) parts.add(lc);
        return parts;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = singleLine(s);
        return t.isEmpty() ? null : t;
    }

    /** Header- and footer-safe text: controls and line/paragraph separators become spaces, format and bidi chars go. */
    public static String singleLine(String s) {
        if (s == null) return "";
        return s.replaceAll("(?U)[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ").replaceAll("(?U)\\p{Cf}", "").strip();
    }
}
