package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateCheckInput.KnownEvent;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The shipped bank and a mutable input builder for rule tests. */
final class RuleFixtures {

    static final QuestionBank BANK = QuestionBankLoader.load(new DefaultResourceLoader());
    static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private RuleFixtures() {}

    static Question q(String id, SourceKind source) {
        return BANK.questions().stream()
                .filter(q -> q.id().equals(id) && q.source() == source)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no question " + id + " " + source));
    }

    static In in() {
        return new In();
    }

    static final class In {
        String city = "Paris";
        String country = "FR";
        String postalCode = "75011";
        String genre = "house & techno";
        String subGenre;
        Integer capacity = 300;
        List<String> lineup = List.of();
        List<KnownEvent> known;
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        LocalDate today = TODAY;
        UUID excludeEventId;

        In city(String city, String country, String postalCode) {
            this.city = city;
            this.country = country;
            this.postalCode = postalCode;
            return this;
        }

        In genre(String genre, String subGenre) {
            this.genre = genre;
            this.subGenre = subGenre;
            return this;
        }

        In capacity(Integer capacity) {
            this.capacity = capacity;
            return this;
        }

        In lineup(String... names) {
            this.lineup = List.of(names);
            return this;
        }

        In known(List<KnownEvent> known) {
            this.known = known;
            return this;
        }

        In org(UUID orgId) {
            this.orgId = orgId;
            return this;
        }

        In excludeEvent(UUID eventId) {
            this.excludeEventId = eventId;
            return this;
        }

        DateCheckInput build() {
            return new DateCheckInput(city, country, postalCode, null, null, genre, subGenre, capacity, 2000L, "club",
                    23, 5, lineup, known, orgId, today, null, null, null, excludeEventId);
        }
    }
}
