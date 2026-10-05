-- What Stripe settled per order, in the destination transfer's currency (NULL until the payout sweep reads it).
ALTER TABLE orders ADD COLUMN settlement_currency VARCHAR(8);
ALTER TABLE orders ADD COLUMN settlement_gross_minor BIGINT;
ALTER TABLE orders ADD COLUMN settlement_fee_minor BIGINT;
-- EUR on the EUR platform never converts, and a destination charge transfers its whole total.
UPDATE orders SET settlement_currency = 'eur', settlement_gross_minor = total_minor,
                  settlement_fee_minor = application_fee_minor
 WHERE lower(currency) = 'eur';
ALTER TABLE orders ADD CONSTRAINT ck_orders_settlement CHECK (
  (settlement_currency IS NULL AND settlement_gross_minor IS NULL AND settlement_fee_minor IS NULL)
  OR (settlement_currency IS NOT NULL AND settlement_gross_minor IS NOT NULL AND settlement_fee_minor IS NOT NULL
      AND settlement_gross_minor >= 0 AND settlement_fee_minor >= 0
      AND settlement_fee_minor <= settlement_gross_minor));
