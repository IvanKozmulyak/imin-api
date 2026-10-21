package com.imin.iminapi.marketing.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Font;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class StoryKitFontsTest {

    @Test
    void bundledFontsLoad() {
        StoryKitFonts fonts = new StoryKitFonts();
        fonts.load();

        assertThat(fonts.available()).isTrue();
    }

    // Tofu boxes instead of "жовт." or "déc." on a story are visible to every fan.
    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "es", "uk"})
    void everyWeightDrawsEveryOverlayStringAndTheLocalizedDateNames(String locale) {
        StoryKitFonts fonts = new StoryKitFonts();
        fonts.load();

        for (StoryKitFonts.Weight weight : StoryKitFonts.Weight.values()) {
            Font font = fonts.font(weight, 40f);
            for (String s : StoryKitText.overlayStrings(locale)) {
                String upper = s.toUpperCase(Locale.forLanguageTag(locale));
                assertThat(font.canDisplayUpTo(s)).as("%s %s '%s'", locale, weight, s).isEqualTo(-1);
                assertThat(font.canDisplayUpTo(upper)).as("%s %s '%s'", locale, weight, upper).isEqualTo(-1);
            }
            for (String s : new String[] {StoryKitText.ticketsLine(locale), "0123456789 · :"}) {
                assertThat(font.canDisplayUpTo(s)).as("%s %s '%s'", locale, weight, s).isEqualTo(-1);
            }
        }
    }
}
