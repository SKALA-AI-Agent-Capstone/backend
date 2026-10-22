-- 검토 전체 승인을 없애면서(2026-10-22) 승인 뒤 Agent 재개 작업(kind=resume)도 쓰지 않는다.
-- 아직 끝나지 않은 재개 작업은 실행하지 않고 닫는다. 이미 끝난 행과 kind 컬럼, 제약은 이력으로 남긴다.
UPDATE app.review_jobs
   SET status = 'failed', request = '{}'::jsonb, last_error = 'REVIEW_APPROVAL_REMOVED', updated_at = now()
 WHERE kind = 'resume' AND status NOT IN ('done', 'failed');
