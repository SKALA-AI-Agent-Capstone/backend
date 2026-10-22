-- Keep failure intent until review state and credit release commit together.
CREATE TABLE app.review_failure_requests (
    review_id uuid NOT NULL REFERENCES app.reviews(id) ON DELETE CASCADE,
    version_no integer NOT NULL CHECK (version_no > 0),
    tenant_id uuid NOT NULL REFERENCES app.tenants(id),
    error_code varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, version_no)
);
CREATE INDEX review_failure_requests_created ON app.review_failure_requests(created_at);
