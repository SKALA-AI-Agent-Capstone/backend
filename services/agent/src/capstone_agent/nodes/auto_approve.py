"""violations가 0건인 clean 케이스 전용 경로. ⑤⑥을 건너뛰고 review를 즉시 승인 처리한다.

decided_by를 비워 "사람이 아니라 시스템이 자동 승인했다"는 걸 구분한다
(2026-10-20 HITL 재설계 4항). 실제 decided_at 시각 바인딩은 AGT-05에서 채운다.
"""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewDecision, ReviewState


async def auto_approve_node(state: ReviewState) -> dict[str, Any]:
    decision: ReviewDecision = {
        "status": "approved",
        "reason": None,
        "decided_by": None,
        "decided_at": None,
    }
    return {"review_decision": decision}
