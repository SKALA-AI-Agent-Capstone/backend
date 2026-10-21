-- 승인 후 Agent 재개도 같은 outbox로 처리한다. run은 광고 분석, resume은 사람 결정 뒤 checkpoint 재개다.
-- 버전마다 최종 결정은 하나이므로 (review_id, version_no, kind)가 작업의 멱등성 키다.
ALTER TABLE app.review_jobs ADD COLUMN kind text NOT NULL DEFAULT 'run';
ALTER TABLE app.review_jobs ADD CONSTRAINT review_jobs_kind_check CHECK (kind IN ('run', 'resume'));
ALTER TABLE app.review_jobs DROP CONSTRAINT review_jobs_pkey;
ALTER TABLE app.review_jobs ADD PRIMARY KEY (review_id, version_no, kind);
