-- 2026-10-26 시료번호만 같은 다른 시험을 개정본으로 내린 증빙을 되돌린다.
-- 같은 시료로 한 다른 시험(피부 자극 시험과 모공 리프팅 시험)은 시료번호가 같다. 예전 판단은 연구번호를 읽지 못한 보고서에서 시료번호로 넘어가
-- 다른 시험의 보고서를 현재 판에서 내렸다. 이제 개정본은 같은 연구 코드로만 판단한다(EvidenceReader.markRevisions).
-- 개정 메모의 코드가 예전 행의 시료번호·관리번호인 연결만 되돌린다. 연구 코드로 이어진 실제 개정본은 그대로 둔다.
WITH wrong AS (
  SELECT n.id AS newer, o.id AS older
  FROM app.product_evidence n
  JOIN app.product_evidence o ON o.id = n.supersedes
  WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements(o.codes) c
    WHERE c->>'label' ~ '시\s*료\s*번\s*호|관\s*리\s*번\s*호'
      AND n.change_note LIKE '같은 코드 ' || (c->>'value') || '%'
  )
), restored AS (
  UPDATE app.product_evidence e SET is_current = true, updated_at = now()
  FROM wrong WHERE e.id = wrong.older
  RETURNING e.id
)
UPDATE app.product_evidence e SET supersedes = NULL, change_note = NULL, updated_at = now()
FROM wrong WHERE e.id = wrong.newer;
