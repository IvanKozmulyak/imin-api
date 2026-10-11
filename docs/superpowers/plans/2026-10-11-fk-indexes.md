# FK indexes (V183)

Goal: index foreign key columns whose parent rows are deleted in normal operation, so the delete's referential check is not a seq scan.

Affected files: `src/main/resources/db/migration/V183__fk_indexes.sql`.

Finding: `campaign_recipients(membership_id)` is already indexed (V116 `ix_campaign_recipients_membership`, leading column membership_id), so it is not re-added.

Added (parent delete that makes each needed): memberships.consumer_id (consumer delete, DSAR erase, countMembershipsByConsumerId); suppression_entries.membership_id (membership delete, cascade); ticket_reservations.tier_id (tier delete); audience_experiments.campaign_id / plan_id / event_id (campaign delete, PlanPruner, event delete); audience_plans.event_id / superseded_by (event/org delete, PlanPruner); audience_plan_publish_invites.org_id (org delete); buyer_saved_events.event_id, meta_pixel_connections.event_id (event delete); three buyer_account_id tables (buyer erasure).

Skipped (parent rows not deleted in normal flow): users (events.created_by, refunds.initiated_by, refund_requests.decided_by, password_reset_tokens.user_id, publish_invites.created_by), refunds/orders children, date_check children, generated_event children.

Test: none; repo tests only migrations that change data, and an index-exists assertion is a constant echo.
Verification: `./mvnw test -Dtest='*Migration*Test,SpringContextGuardTest'`.
