-- One reservation per review version. Keep the audit record when a review is deleted.
CREATE TABLE app.review_credit_charges (
  review_id uuid NOT NULL,
  version_no integer NOT NULL CHECK (version_no > 0),
  tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  user_id uuid NOT NULL REFERENCES app.users(id),
  credits integer NOT NULL CHECK (credits > 0),
  status text NOT NULL CHECK (status IN ('reserved', 'charged', 'released')),
  created_at timestamptz NOT NULL DEFAULT now(),
  settled_at timestamptz,
  PRIMARY KEY (review_id, version_no)
);
CREATE INDEX review_credit_charges_user_created ON app.review_credit_charges(user_id, created_at DESC);
