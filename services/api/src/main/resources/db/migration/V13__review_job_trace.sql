-- 마지막으로 실패한 Agent 실행의 LangSmith run id. 재시도 중이거나 실패한 작업을 추적 기록에서 찾는다.
-- 추적이 꺼져 있거나 Agent에 닿지 못한 실패는 비워 둔다. 완료되면 결과의 traceId가 대신한다.
ALTER TABLE app.review_jobs ADD COLUMN last_trace_id text;
