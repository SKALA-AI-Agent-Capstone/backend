CREATE TABLE app.tenants (id uuid PRIMARY KEY, name text NOT NULL);
CREATE TABLE app.users (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  email text UNIQUE NOT NULL, name text NOT NULL, password_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('author','reviewer','admin'))
);
CREATE TABLE app.sessions (
  id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app.users(id), expires_at timestamptz NOT NULL
);
CREATE TABLE app.reviews (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  author_id uuid NOT NULL REFERENCES app.users(id), document jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reviews_tenant_updated ON app.reviews(tenant_id, updated_at DESC);
CREATE TABLE app.review_assets (
  id uuid PRIMARY KEY, review_id uuid NOT NULL REFERENCES app.reviews(id) ON DELETE CASCADE,
  version_no integer NOT NULL, name text NOT NULL, mime_type text NOT NULL,
  role text NOT NULL, doc_type text, size_bytes integer NOT NULL, sha256 text NOT NULL
);
CREATE TABLE app.review_runs (
  review_id uuid NOT NULL REFERENCES app.reviews(id) ON DELETE CASCADE,
  version_no integer NOT NULL, token_hash text NOT NULL,
  PRIMARY KEY (review_id, version_no)
);
