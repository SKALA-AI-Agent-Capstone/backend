-- 증빙 읽기 작업의 outbox. 검토의 review_jobs와 같은 방식(DB가 작업 상태의 기준이고 RabbitMQ는 알림)이지만
-- 검토 큐와 섞이지 않게 따로 둔다. 증빙 읽기는 몇 분 걸리는 스캔 OCR이라 검토 작업을 막으면 안 된다.
CREATE TABLE app.evidence_jobs (
    evidence_id uuid PRIMARY KEY REFERENCES app.product_evidence(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_error text,
    CONSTRAINT evidence_jobs_status_check CHECK (status IN ('pending', 'published', 'running', 'retry', 'done', 'failed'))
);

CREATE INDEX evidence_jobs_dispatch_idx ON app.evidence_jobs(next_attempt_at) WHERE status IN ('pending', 'retry');
