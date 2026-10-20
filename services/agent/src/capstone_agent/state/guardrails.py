# Notion "Agent Graph 상세 설계" 페이지의 예시 수치. 확정치가 아니며 팀 합의로 바뀔 수 있다.
MAX_TOOL_CALLS_TOTAL = 8
LOOP_TIMEOUT_SECONDS = 60
MAX_SAME_TOOL_CALLS = 2

# 개별 MCP tool 호출 실패 시 재시도 횟수. Vision 실패는 재시도 없이 전체 중단(fail-fast)이며
# 이 상수가 아니라 Vision adapter 자체의 분기로 구현한다.
TOOL_FAILURE_RETRY_LIMIT = 2
REPORT_GENERATION_RETRY_LIMIT = 3

# 2026-10-20 HITL 재설계에서 정한 값. 위 네 개(Notion "Agent Graph 상세 설계" 페이지 원본)와
# 출처가 다르다 — ⑤ 자가검증 루프가 violation 하나당 반복 생성할 수 있는 최대 횟수이며,
# 아직 Notion에 반영되기 전의 재설계 확정 과정에서 합의한 예시값이다.
REWRITE_SELF_VERIFY_RETRY_LIMIT = 3
