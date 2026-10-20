-- V4 이전에 저장된 결과를 app.violations로 옮긴다. 이후 저장 경로는 ViolationService가 채운다.
-- 재검토 버전 구조가 없으므로 최신 버전이 분석 완료(done)인 review의 findings만 옮긴다.
-- 각 JSONB finding에는 violationId를 넣어 프론트가 finding과 violation 행을 연결할 수 있게 한다.
-- hitl_status는 V4가 이미 옮겼으므로 바꾸지 않는다. 과거에 위반 0건이던 review도 자동 승인하지 않는다.
-- app_user는 임시 테이블 권한이 없으므로 한 문장의 데이터 변경 CTE로 처리한다(WITH 안의 INSERT는 본문 참조와 무관하게 실행된다).

WITH backfill AS MATERIALIZED (
  SELECT r.id AS review_id, f.ord, f.finding, gen_random_uuid() AS violation_id
  FROM app.reviews r
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(r.document->'versions'->-1->'result'->'findings') = 'array'
         THEN r.document->'versions'->-1->'result'->'findings' ELSE '[]'::jsonb END
  ) WITH ORDINALITY AS f(finding, ord)
  WHERE r.document->'versions'->-1->>'status' = 'done'
    AND btrim(coalesce(f.finding->>'phrase', '')) <> ''
    AND NOT EXISTS (SELECT 1 FROM app.violations v WHERE v.review_id = r.id)
), rewrites AS MATERIALIZED (
  SELECT b.violation_id,
         row_number() OVER (PARTITION BY b.violation_id ORDER BY w.ord) AS attempt_no,
         w.text
  FROM backfill b
  CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE WHEN jsonb_typeof(b.finding->'rewrites') = 'array' THEN b.finding->'rewrites' ELSE '[]'::jsonb END
  ) WITH ORDINALITY AS w(text, ord)
  WHERE btrim(w.text) <> ''
), inserted_violations AS (
  INSERT INTO app.violations (id, review_id, text_excerpt, severity, rewrite_suggestion, rewrite_attempt_count)
  SELECT b.violation_id, b.review_id, b.finding->>'phrase',
         CASE WHEN lower(b.finding->>'severity') IN ('high','medium','low') THEN lower(b.finding->>'severity') ELSE 'medium' END,
         (SELECT w.text FROM rewrites w WHERE w.violation_id = b.violation_id ORDER BY w.attempt_no DESC LIMIT 1),
         (SELECT count(*) FROM rewrites w WHERE w.violation_id = b.violation_id)
  FROM backfill b
  RETURNING id
), inserted_rewrites AS (
  INSERT INTO app.violation_rewrites (id, violation_id, attempt_no, text, requested_by_human)
  SELECT gen_random_uuid(), w.violation_id, w.attempt_no, w.text, false
  FROM rewrites w JOIN inserted_violations v ON v.id = w.violation_id
)
UPDATE app.reviews r
SET document = jsonb_set(
  r.document,
  ARRAY['versions', (jsonb_array_length(r.document->'versions') - 1)::text, 'result', 'findings'],
  (SELECT jsonb_agg(CASE WHEN b.violation_id IS NULL THEN f.finding
                         ELSE f.finding || jsonb_build_object('violationId', b.violation_id::text) END
                    ORDER BY f.ord)
   FROM jsonb_array_elements(r.document->'versions'->-1->'result'->'findings') WITH ORDINALITY AS f(finding, ord)
   LEFT JOIN backfill b ON b.review_id = r.id AND b.ord = f.ord))
WHERE r.id IN (SELECT review_id FROM backfill);
