package com.imin.iminapi.refund;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RefundReferenceGeneratorTest {

    private final RefundReferenceGenerator gen = new RefundReferenceGenerator(
            Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void has_the_documented_shape_with_the_two_digit_year() {
        String ref = gen.next();
        assertThat(ref).matches("^REQ-[A-Z2-9]{4}-26$");
    }

    @Test
    void never_emits_a_character_a_human_could_misread() {
        // 0/O and 1/I are the whole reason this code exists instead of a UUID fragment:
        // it has to survive being read out over a phone and written down by someone else.
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            for (char c : gen.next().substring(4, 8).toCharArray()) seen.add(c);
        }
        assertThat(seen).doesNotContain('0', 'O', '1', 'I');
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                RefundReferenceGenerator.ALPHABET.chars().mapToObj(c -> (char) c).toList());
    }

    @Test
    void draws_from_the_whole_space_rather_than_repeating() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 500; i++) refs.add(gen.next());
        // 32^4 space: 500 draws colliding more than a handful of times means the RNG is broken.
        assertThat(refs).hasSizeGreaterThan(490);
    }

    @Test
    void normalize_accepts_what_a_human_would_actually_type() {
        assertThat(RefundReferenceGenerator.normalize("REQ-8K2M-26")).isEqualTo("REQ-8K2M-26");
        assertThat(RefundReferenceGenerator.normalize("  req-8k2m-26 ")).isEqualTo("REQ-8K2M-26");
        assertThat(RefundReferenceGenerator.normalize("8K2M-26")).isEqualTo("REQ-8K2M-26");
        assertThat(RefundReferenceGenerator.normalize("REQ 8K2M 26")).isEqualTo("REQ-8K2M-26");
    }

    @Test
    void normalize_also_accepts_the_V81_legacy_backfill_shape() {
        // Rows that predate the generator carry a UUID-derived REQ-XXXX-XXXX code.
        assertThat(RefundReferenceGenerator.normalize("REQ-8K2M-4B7C")).isEqualTo("REQ-8K2M-4B7C");
    }

    @Test
    void normalize_matches_its_own_output_in_every_year_including_past_2030() {
        // The year suffix is plain decimal, but SHAPE was built from an alphabet that excludes
        // 0 and 1 — so from 2030-01-01 the pattern stopped matching the codes this class had
        // just minted. normalize() returned null, the case/prefix-forgiving reference lookup
        // fell through to the buyer-email LIKE, and an operator searching a code the customer
        // had just read out found nothing. Walk a century; every year must round-trip.
        for (int year = 2026; year <= 2126; year++) {
            Clock clock = Clock.fixed(Instant.parse(year + "-03-04T10:00:00Z"), ZoneOffset.UTC);
            String ref = new RefundReferenceGenerator(clock).next();
            assertThat(RefundReferenceGenerator.normalize(ref))
                    .as("a %d code must survive its own normalize()", year)
                    .isEqualTo(ref);
            assertThat(RefundReferenceGenerator.normalize(ref.toLowerCase(java.util.Locale.ROOT)))
                    .as("...including as the customer typed it", year)
                    .isEqualTo(ref);
        }
    }

    @Test
    void the_two_families_of_code_stay_distinguishable() {
        // Generated codes end in a 2-digit year, V81-backfilled ones in 4 alphabet symbols.
        // Different lengths, so no generated code can ever collide with a legacy one.
        assertThat(RefundReferenceGenerator.normalize("REQ-8K2M-30")).isEqualTo("REQ-8K2M-30");
        assertThat(RefundReferenceGenerator.normalize("REQ-8K2M-4B7C")).isEqualTo("REQ-8K2M-4B7C");
        // Three characters is neither shape.
        assertThat(RefundReferenceGenerator.normalize("REQ-8K2M-4B7")).isNull();
    }

    @Test
    void normalize_rejects_anything_that_is_not_a_reference() {
        assertThat(RefundReferenceGenerator.normalize(null)).isNull();
        assertThat(RefundReferenceGenerator.normalize("   ")).isNull();
        assertThat(RefundReferenceGenerator.normalize("buyer@example.com")).isNull();
        // Ambiguous characters are not in the alphabet, so a mistyped O/0 is not a match.
        assertThat(RefundReferenceGenerator.normalize("REQ-8K0M-26")).isNull();
    }
}
