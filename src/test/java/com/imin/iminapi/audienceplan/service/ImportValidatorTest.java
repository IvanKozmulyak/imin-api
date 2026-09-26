package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.service.CsvContactParser.RawContact;
import com.imin.iminapi.audienceplan.service.ImportValidator.Decision;
import com.imin.iminapi.audienceplan.service.ImportValidator.Outcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ImportValidatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-27");

    private static RawContact row(String status, String platform, String exportDate, String proof) {
        return new RawContact(2, "a@x.com", null, null, platform, exportDate, "Night A", "2026-08-01",
                status, proof);
    }

    private static Decision decide(RawContact r) {
        return ImportValidator.forImport(false, TODAY).decide(r);
    }

    @Test
    void opted_in_with_proof_platform_and_date_is_explicit() {
        Decision d = decide(row(" Opted_In ", " shotgun ", "2026-09-01", " proof-1 "));
        assertThat(d.outcome()).isEqualTo(Outcome.EXPLICIT);
        assertThat(d.reason()).isNull();
        assertThat(d.marketingStatus()).isEqualTo("opted_in");
        assertThat(d.sourcePlatform()).isEqualTo("shotgun");
        assertThat(d.exportDate()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(d.lastPurchaseDate()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(d.events()).isEqualTo("Night A");
        assertThat(d.proofRef()).isEqualTo("proof-1");
    }

    @Test
    void a_row_without_status_has_no_basis() {
        Decision d = decide(row(null, "shotgun", "2026-09-01", "proof"));
        assertThat(d.outcome()).isEqualTo(Outcome.NO_BASIS);
        assertThat(d.reason()).isEqualTo("not_opted_in");
        assertThat(d.marketingStatus()).isEqualTo("none");
    }

    @Test
    void any_other_status_has_no_basis() {
        Decision d = decide(row("yes", "shotgun", "2026-09-01", "proof"));
        assertThat(d.outcome()).isEqualTo(Outcome.NO_BASIS);
        assertThat(d.reason()).isEqualTo("not_opted_in");
        assertThat(d.marketingStatus()).isEqualTo("none");
    }

    @Test
    void unsubscribed_status_asks_for_suppression() {
        Decision d = decide(row("UNSUBSCRIBED", null, null, null));
        assertThat(d.outcome()).isEqualTo(Outcome.UNSUBSCRIBE);
        assertThat(d.reason()).isEqualTo("unsubscribed");
        assertThat(d.marketingStatus()).isEqualTo("unsubscribed");
    }

    @Test
    void opted_in_without_proof_ref_has_no_basis() {
        assertThat(decide(row("opted_in", "shotgun", "2026-09-01", " ")).reason()).isEqualTo("missing_proof");
    }

    @Test
    void opted_in_without_source_platform_has_no_basis() {
        assertThat(decide(row("opted_in", null, "2026-09-01", "proof")).reason()).isEqualTo("missing_proof");
    }

    @Test
    void opted_in_without_export_date_has_no_basis() {
        Decision d = decide(row("opted_in", "shotgun", null, "proof"));
        assertThat(d.outcome()).isEqualTo(Outcome.NO_BASIS);
        assertThat(d.reason()).isEqualTo("missing_proof");
    }

    @Test
    void unparseable_export_date_has_no_basis() {
        Decision d = decide(row("opted_in", "shotgun", "01/09/2026", "proof"));
        assertThat(d.outcome()).isEqualTo(Outcome.NO_BASIS);
        assertThat(d.reason()).isEqualTo("invalid_export_date");
        assertThat(d.exportDate()).isNull();
    }

    @Test
    void future_export_date_has_no_basis() {
        Decision d = decide(row("opted_in", "shotgun", "2026-09-28", "proof"));
        assertThat(d.reason()).isEqualTo("invalid_export_date");
        assertThat(d.exportDate()).isNull();
    }

    @Test
    void export_date_of_today_is_accepted() {
        assertThat(decide(row("opted_in", "shotgun", "2026-09-27", "proof")).outcome()).isEqualTo(Outcome.EXPLICIT);
    }

    @Test
    void unparseable_last_purchase_date_is_dropped_without_affecting_the_basis() {
        RawContact r = new RawContact(2, "a@x.com", null, null, "shotgun", "2026-09-01", null, "last week",
                "opted_in", "proof");
        Decision d = decide(r);
        assertThat(d.outcome()).isEqualTo(Outcome.EXPLICIT);
        assertThat(d.lastPurchaseDate()).isNull();
    }

    @Test
    void long_values_are_clipped() {
        Decision d = decide(row("opted_in", "p".repeat(80), "2026-09-01", "x".repeat(600)));
        assertThat(d.sourcePlatform()).hasSize(64);
        assertThat(d.proofRef()).hasSize(500);
    }

    @Test
    void rows_above_the_cap_are_downgraded_without_import_level_proof() {
        ImportValidator v = ImportValidator.forImport(false, TODAY);
        RawContact ok = row("opted_in", "shotgun", "2026-09-01", "proof");
        for (int i = 0; i < ImportValidator.SUBSCRIBED_CAP; i++) {
            assertThat(v.decide(ok).outcome()).isEqualTo(Outcome.EXPLICIT);
        }
        Decision over = v.decide(ok);
        assertThat(over.outcome()).isEqualTo(Outcome.NO_BASIS);
        assertThat(over.reason()).isEqualTo("subscribed_cap");
        assertThat(over.proofRef()).isEqualTo("proof");
    }

    @Test
    void import_level_proof_lifts_the_cap() {
        ImportValidator v = ImportValidator.forImport(true, TODAY);
        RawContact ok = row("opted_in", "shotgun", "2026-09-01", "proof");
        for (int i = 0; i < ImportValidator.SUBSCRIBED_CAP; i++) v.decide(ok);
        assertThat(v.decide(ok).outcome()).isEqualTo(Outcome.EXPLICIT);
    }

    @Test
    void rows_without_basis_do_not_use_up_the_cap() {
        ImportValidator v = ImportValidator.forImport(false, TODAY);
        RawContact none = row(null, null, null, null);
        for (int i = 0; i < ImportValidator.SUBSCRIBED_CAP + 5; i++) v.decide(none);
        assertThat(v.decide(row("opted_in", "shotgun", "2026-09-01", "proof")).outcome())
                .isEqualTo(Outcome.EXPLICIT);
    }

    @Test
    void inspect_never_counts_towards_the_cap() {
        ImportValidator v = ImportValidator.forImport(false, TODAY);
        RawContact ok = row("opted_in", "shotgun", "2026-09-01", "proof");
        for (int i = 0; i < ImportValidator.SUBSCRIBED_CAP + 5; i++) {
            assertThat(v.inspect(ok).outcome()).isEqualTo(Outcome.EXPLICIT);
        }
        assertThat(v.decide(ok).outcome()).isEqualTo(Outcome.EXPLICIT);
    }
}
