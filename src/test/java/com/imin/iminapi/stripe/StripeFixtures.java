package com.imin.iminapi.stripe;

import com.stripe.model.v2.core.Account;

import java.util.List;

/**
 * Builds com.stripe.model.v2.core.Account instances by feeding raw JSON through the
 * SDK's deserializer — the Account class has no public constructors. Each helper
 * mirrors a real Stripe scenario the mirror must handle.
 *
 * <p>The v2 SDK shape: {@code requirements.entries[]} is a flat list where each
 * Entry has a {@code description} (machine-readable code), a {@code minimum_deadline.status}
 * (one of currently_due / eventually_due / past_due), and an {@code awaiting_action_from}.
 * There is no nested {@code requirements[].field_name} array.
 */
final class StripeFixtures {

    private StripeFixtures() {}

    static Account accountActive(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "active" } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "verified" } },
                "entries": []
              }
            }
            """.formatted(id));
    }

    static Account accountPendingVerification(String id) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "pending" } }
                  }
                }
              },
              "requirements": {
                "summary": { "minimum_deadline": { "status": "pending_verification" } },
                "entries": []
              }
            }
            """.formatted(id));
    }

    static Account accountWithCurrentlyDue(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "restricted" } }
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

    static Account accountOnboarding(String id, List<String> fields) {
        return parse("""
            {
              "id": "%s",
              "configuration": {
                "recipient": {
                  "capabilities": {
                    "stripe_balance": { "stripe_transfers": { "status": "unverified" } }
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
