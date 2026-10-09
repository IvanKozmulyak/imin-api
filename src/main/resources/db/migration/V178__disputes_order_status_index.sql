-- The Orders tab resolves each order's dispute status by order id; disputes had no index on it.
CREATE INDEX IF NOT EXISTS disputes_order_status_idx ON disputes (order_id, status);
