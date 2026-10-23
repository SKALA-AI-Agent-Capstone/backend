-- 검토 전체 승인과 Agent 재개를 없앤 뒤(2026-10-22, V15) 남아 있던 것을 지운다. 팀 확인(2026-10-23).
-- 지운 뒤에는 되돌릴 수 없다. 예전 승인 결과와 코멘트는 검토 문서(JSONB)의 versions[].approval에만 남는다.

-- 1. 재개 작업. V15가 모두 failed로 닫아 두었다. 분석(run) 작업만 남긴다.
DELETE FROM app.review_jobs WHERE kind <> 'run';
ALTER TABLE app.review_jobs DROP CONSTRAINT review_jobs_pkey;
ALTER TABLE app.review_jobs ADD PRIMARY KEY (review_id, version_no);
ALTER TABLE app.review_jobs DROP COLUMN kind; -- review_jobs_kind_check도 함께 지워진다

-- 2. 검토 단위 승인 컬럼. reviews_decided_at_required 검사와 decided_by 외래 키도 함께 지워진다.
ALTER TABLE app.reviews
  DROP COLUMN hitl_status,
  DROP COLUMN hitl_comment,
  DROP COLUMN decided_by,
  DROP COLUMN decided_at;
