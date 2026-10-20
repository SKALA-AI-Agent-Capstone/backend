-- Each member owns a credit balance. Preserve completed topups while moving from
-- the earlier tenant-wide display to per-user balances.
ALTER TABLE app.users
  ADD COLUMN credit_balance integer NOT NULL DEFAULT 0 CHECK (credit_balance >= 0);

ALTER TABLE app.credit_topups
  ADD COLUMN user_id uuid REFERENCES app.users(id);

UPDATE app.credit_topups t
SET user_id = o.user_id
FROM app.credit_orders o
WHERE o.id = t.order_id;

ALTER TABLE app.credit_topups ALTER COLUMN user_id SET NOT NULL;
CREATE INDEX credit_topups_user_created ON app.credit_topups(user_id, created_at DESC);

UPDATE app.users u
SET credit_balance = COALESCE((
  SELECT sum(t.credits) FROM app.credit_topups t WHERE t.user_id = u.id
), 0);
