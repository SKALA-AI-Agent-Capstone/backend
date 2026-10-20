-- 세션은 로그인 1회(Refresh Token 계열)를 뜻한다. Access JWT의 sid 클레임이 이 행을 가리킨다.
-- 기존 2시간 access JWT는 sid가 없으므로 모두 무효화한다.
DELETE FROM app.sessions;
ALTER TABLE app.sessions ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- 원문 토큰은 쿠키에만 있고 DB에는 SHA-256 해시만 둔다.
-- 회전된 토큰은 used_at을 남겨 재사용을 감지하고, 세션이 지워지면 함께 지운다.
CREATE TABLE app.refresh_tokens (
  token_hash text PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES app.sessions(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);
CREATE INDEX refresh_tokens_session_idx ON app.refresh_tokens(session_id);
