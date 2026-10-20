CREATE TABLE IF NOT EXISTS app.credit_orders (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  user_id uuid NOT NULL REFERENCES app.users(id),
  provider text NOT NULL CHECK (provider IN ('toss','kakao')),
  credits integer NOT NULL CHECK (credits > 0),
  amount_krw integer NOT NULL CHECK (amount_krw > 0),
  status text NOT NULL CHECK (status IN ('pending','paid','cancelled')),
  provider_ref text,
  created_at timestamptz NOT NULL DEFAULT now(),
  paid_at timestamptz
);
CREATE INDEX IF NOT EXISTS credit_orders_tenant_created ON app.credit_orders(tenant_id, created_at DESC);
CREATE TABLE IF NOT EXISTS app.credit_topups (
  order_id uuid PRIMARY KEY REFERENCES app.credit_orders(id),
  tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  credits integer NOT NULL CHECK (credits > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS credit_topups_tenant ON app.credit_topups(tenant_id);
