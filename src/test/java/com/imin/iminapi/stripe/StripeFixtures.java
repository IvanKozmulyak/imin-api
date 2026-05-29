package com.imin.iminapi.stripe;

import com.stripe.model.v2.core.Account;

import java.util.List;

/**
 * Builds com.stripe.model.v2.core.Account instances by feeding raw JSON through the
 * SDK's deserializer — the Account class has no public constructors. Each helper
 * mirrors a real Stripe scenario the mirror must handle.
 *
 * <p>The v2 SDK shape:
 * <ul>
 *   <li>{@code requirements.entries[]} is a flat list where each Entry has a
 *       {@code description} (machine-readable code) and a {@code minimum_deadline.status}
 *       — which per the SDK is ONLY one of {@code currently_due} / {@code eventually_due} /
 *       {@code past_due}. It is a deadline-timing field; it NEVER carries verification
 *       lifecycle values like "verified"/"pending_verification".</li>
 *   <li>The verification lifecycle lives on the capability instead:
 *       {@code configuration.recipient.capabilities.stripe_balance.stripe_transfers.status}
 *       ∈ {@code active|pending|restricted|unsupported}, with a {@code status_details[]}
 *       list whose {@code code} ∈ {determining_status, requirements_past_due,
 *       requirements_pending_verification, restricted_other, unsupported_*} and
 *       {@code resolution} ∈ {contact_stripe, no_resolution, provide_info}.</li>
 * </ul>
 * These fixtures therefore model submission/verification on the capability, which is
 * what {@link StripeConnectStatusMirror} now reads.
 */
final class StripeFixtures {

    private StripeFixtures() {}

    /** Fully onboarded: transfers active, no outstanding requirements. → ACTIVE. */
    static Account accountActive(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "active", "status_details": [] } }
                  }
                }
              },
              "requirements": {
                "summary": {},
                "entries": []
              }
            }
            """.formatted(id));
    }

    /**
     * Organizer submitted everything; Stripe is verifying. The capability sits at
     * {@code pending} with {@code status_details.code = requirements_pending_verification}
     * and {@code awaiting_action_from = stripe}. This is the REAL in-review shape (Stripe
     * never reports "pending_verification" on a deadline). → PENDING_VERIFICATION.
     */
    static Account accountPendingVerification(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": {
                      "status": "pending",
                      "status_details": [ { "code": "requirements_pending_verification", "resolution": "no_resolution" } ]
                    } }
                  }
                }
              },
              "requirements": {
                "summary": {},
                "entries": []
              }
            }
            """.formatted(id));
    }

    /**
     * A brand-new recipient account that has only *requested* the transfers capability and
     * submitted nothing yet: capability {@code restricted} (not pending/active — Stripe has
     * nothing to verify), and every outstanding requirement sits at {@code eventually_due}
     * (Stripe's baseline — no hard deadline until activity/onboarding forces one). This is
     * the state an organizer who abandoned the hosted onboarding lands in. → ONBOARDING.
     */
    static Account accountFreshEventuallyDue(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "restricted", "status_details": [] } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "eventually_due" } },
                "entries": [ %s ]
              }
            }
            """.formatted(id, entriesJson(fields, "eventually_due")));
    }

    /**
     * Restricted with outstanding {@code currently_due} requirements. Used with
     * {@code details_submitted} preset true (i.e. the org previously reached pending/active)
     * so the derivation lands on RESTRICTED rather than ONBOARDING.
     */
    static Account accountWithCurrentlyDue(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": {
                      "status": "restricted",
                      "status_details": [ { "code": "requirements_past_due", "resolution": "provide_info" } ]
                    } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "currently_due" } },
                "entries": [ %s ]
              }
            }
            """.formatted(id, entriesJson(fields, "currently_due")));
    }

    /** Restricted + currently_due but nothing submitted yet. → ONBOARDING. */
    static Account accountOnboarding(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "restricted", "status_details": [] } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "currently_due" } },
                "entries": [ %s ]
              }
            }
            """.formatted(id, entriesJson(fields, "currently_due")));
    }

    /**
     * Terminal/unrecoverable: the transfers capability is {@code unsupported} (e.g. the
     * account's country/business/entity type can't receive transfers), with a
     * {@code contact_stripe} resolution. → DISABLED, with disabledReason carrying the code.
     */
    static Account accountDisabledUnsupported(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": {
                      "status": "unsupported",
                      "status_details": [ { "code": "unsupported_country", "resolution": "contact_stripe" } ]
                    } }
                  }
                }
              },
              "requirements": {
                "summary": {},
                "entries": []
              }
            }
            """.formatted(id));
    }

    private static String entriesJson(List<String> fields, String status) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("""
                { "description": "%s", "minimum_deadline": { "status": "%s" } }
                """.formatted(fields.get(i), status));
        }
        return sb.toString();
    }

    private static Account parse(String json) {
        return com.stripe.net.ApiResource.GSON.fromJson(json, Account.class);
    }
}
