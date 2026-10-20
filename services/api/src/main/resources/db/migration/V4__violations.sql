-- 문구(위반 후보)별 HITL 결정과 review 전체 판단을 정규화한다.
-- 재검토 버전 구조는 두지 않는다: violation은 review_id만 참조한다.
-- 현재 코드는 아직 reviews.document JSONB를 읽고 쓰므로, 이 테이블들은 저장 경로 전환 전까지 비어 있다.

CREATE TABLE app.violations (
  id uuid PRIMARY KEY,
  review_id uuid NOT NULL REFERENCES app.reviews(id) ON DELETE CASCADE,
  text_excerpt text NOT NULL,
  severity text NOT NULL CHECK (severity IN ('high','medium','low')),
  -- ⑤ 자가검증 loop가 만든 최신 자동 리라이트 제안. 이력은 app.violation_rewrites.
  rewrite_suggestion text,
  rewrite_attempt_count integer NOT NULL DEFAULT 0,
  -- NULL이면 미결정. kept=원문 유지, rewritten=자동 제안 채택, rewritten_manual=사람이 직접 수정.
  final_resolution text CHECK (final_resolution IN ('kept','rewritten','rewritten_manual')),
  final_text text,
  -- kept와 rewritten은 확정 시 true. rewritten_manual은 재검증을 통과해야 true.
  final_text_validated boolean NOT NULL DEFAULT false,
  decided_by uuid REFERENCES app.users(id),
  decided_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((final_resolution IS NULL) = (final_text IS NULL)),
  CHECK (final_resolution <> 'rewritten_manual' OR final_text_validated)
);
CREATE INDEX violations_review_idx ON app.violations(review_id);

CREATE TABLE app.violation_rewrites (
  id uuid PRIMARY KEY,
  violation_id uuid NOT NULL REFERENCES app.violations(id) ON DELETE CASCADE,
  attempt_no integer NOT NULL,
  text text NOT NULL,
  -- true면 사람의 "다시쓰기" 요청으로 생성. 사람 재시도 한도(M회)는 이 행 수로 판정한다.
  requested_by_human boolean NOT NULL DEFAULT false,
  retry_note text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (violation_id, attempt_no),
  CHECK (requested_by_human OR retry_note IS NULL)
);

-- review 전체에 대한 사람의 판단. decided_by가 NULL이고 approved이면 위반 0건 자동 승인이다.
-- "모든 violation이 결정된 뒤에만 approved"는 앱에서 검증한다.
ALTER TABLE app.reviews
  ADD COLUMN hitl_status text NOT NULL DEFAULT 'pending'
    CHECK (hitl_status IN ('pending','approved','rejected','revision_requested')),
  ADD COLUMN hitl_comment text,
  ADD COLUMN decided_by uuid REFERENCES app.users(id),
  ADD COLUMN decided_at timestamptz,
  ADD CONSTRAINT reviews_decided_at_required CHECK (hitl_status = 'pending' OR decided_at IS NOT NULL);

-- 기존 JSONB의 최신 버전 승인 상태를 옮긴다. changes_requested는 revision_requested로 바꾼다.
-- JSONB 이력에는 사용자 ID가 없고 이름만 있으므로, 같은 기업에서 이름이 하나로 맞을 때만 decided_by를 채운다.
WITH latest AS (
  SELECT r.id, r.tenant_id, r.document->'versions'->-1->'approval' AS approval
  FROM app.reviews r
), decided AS (
  SELECT id, tenant_id,
         CASE approval->>'state' WHEN 'changes_requested' THEN 'revision_requested' ELSE approval->>'state' END AS state,
         approval->'history'->-1 AS last
  FROM latest
  WHERE approval->>'state' IN ('approved','rejected','changes_requested')
)
UPDATE app.reviews r SET
  hitl_status = d.state,
  hitl_comment = NULLIF(d.last->>'comment',''),
  decided_at = COALESCE((d.last->>'at')::timestamptz, r.updated_at),
  decided_by = (SELECT min(u.id::text)::uuid FROM app.users u
                WHERE u.tenant_id = d.tenant_id AND u.name = d.last->>'by'
                HAVING count(*) = 1)
FROM decided d WHERE r.id = d.id;
