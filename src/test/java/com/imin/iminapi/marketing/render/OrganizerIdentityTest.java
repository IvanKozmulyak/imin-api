package com.imin.iminapi.marketing.render;

import com.imin.iminapi.model.Organization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizerIdentityTest {

    private static Organization org(String name, String brand, String legalName, String legalContact) {
        Organization o = new Organization();
        o.setName(name);
        o.setBrandName(brand);
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        return o;
    }

    @Test
    void of_usesBrandNameAsDisplayName() {
        OrganizerIdentity id = OrganizerIdentity.of(org("Org Ltd", "Night", "Night SAS", "legal@night.test"));
        assertThat(id).isEqualTo(new OrganizerIdentity("Night", "Night SAS", "legal@night.test"));
    }

    @Test
    void of_fallsBackToOrgNameWhenBrandBlank() {
        assertThat(OrganizerIdentity.of(org("Org Ltd", " ", null, null)).name()).isEqualTo("Org Ltd");
    }

    @Test
    void of_nullOrgIsNone() {
        assertThat(OrganizerIdentity.of(null)).isEqualTo(OrganizerIdentity.NONE);
        assertThat(OrganizerIdentity.NONE.footerParts()).isEmpty();
    }

    @Test
    void footerParts_dropsLegalNameThatRepeatsTheDisplayName() {
        assertThat(new OrganizerIdentity("Night SAS", "night sas", "legal@night.test").footerParts())
                .containsExactly("Night SAS", "legal@night.test");
    }

    @Test
    void footerParts_replacesControlCharactersAndTrims() {
        assertThat(new OrganizerIdentity(" Night\r\nOrg ", "Night SAS", "a\tb").footerParts())
                .containsExactly("Night  Org", "Night SAS", "a b");
    }

    @Test
    void footerParts_replacesUnicodeSeparatorsAndDropsFormatAndBidiCharacters() {
        // U+2028 line sep, U+2029 para sep, U+0085 NEL, U+202E RLO, U+200B ZWSP, U+2066 LRI
        assertThat(new OrganizerIdentity("Night\u2028Org", "Night\u202E SAS\u2066", "a\u2029b\u0085c\u200B").footerParts())
                .containsExactly("Night Org", "Night SAS", "a b c");
    }

    @Test
    void singleLine_onlyFormatCharactersIsEmpty() {
        assertThat(OrganizerIdentity.singleLine("\u202E\u200B\u2028")).isEmpty();
        assertThat(new OrganizerIdentity("\u202E", null, null).footerParts()).isEmpty();
    }
}
