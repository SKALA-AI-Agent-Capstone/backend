CREATE TABLE app.review_jobs (
    review_id uuid NOT NULL REFERENCES app.reviews(id) ON DELETE CASCADE,
    version_no integer NOT NULL,
    request jsonb NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_error text,
    PRIMARY KEY (review_id, version_no),
    CONSTRAINT review_jobs_status_check CHECK (status IN ('pending', 'published', 'running', 'retry', 'done', 'failed'))
);

CREATE INDEX review_jobs_dispatch_idx ON app.review_jobs(next_attempt_at)
    WHERE status IN ('pending', 'retry');
