package com.imin.iminapi.marketing.dto;

/**
 * SendGate dry-run result for the composer Audience step (spec §2.4 preview-audience).
 * No members are materialized; this is a live count only, re-run at send time in Phase 2/4.
 */
public record PreviewAudienceResponse(int sendable, Excluded excluded) {

    public record Excluded(
            int noBasis,
            int unsubscribed,
            int marketingSuppressed,
            int deliverabilitySuppressed,
            int noPhone,  // always 0 for email in Phase 1; populated for SMS in Phase 3
            int noEmail,  // spec §4 "no deliverable address" (email) — no address on file to send to
            // Send-path skips applied after SendGate, the same way RecipientMaterializer applies them.
            int experimentHoldout,
            int eventCap,
            int monthlyCap,
            int consentGate,
            // An address on file that cannot be mailed: malformed or on a reserved test domain (email only).
            int invalidAddress
    ) {
        /** SendGate counts alone; the send-path counts are 0 because no send-path guard ran. */
        public Excluded(int noBasis, int unsubscribed, int marketingSuppressed, int deliverabilitySuppressed,
                        int noPhone, int noEmail) {
            this(noBasis, unsubscribed, marketingSuppressed, deliverabilitySuppressed, noPhone, noEmail, 0, 0, 0, 0, 0);
        }

        /** SendGate counts and the send-path skips, with no invalid address counted. */
        public Excluded(int noBasis, int unsubscribed, int marketingSuppressed, int deliverabilitySuppressed,
                        int noPhone, int noEmail, int experimentHoldout, int eventCap, int monthlyCap,
                        int consentGate) {
            this(noBasis, unsubscribed, marketingSuppressed, deliverabilitySuppressed, noPhone, noEmail,
                    experimentHoldout, eventCap, monthlyCap, consentGate, 0);
        }
    }
}
