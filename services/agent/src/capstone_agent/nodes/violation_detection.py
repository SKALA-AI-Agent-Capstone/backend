"""② 위반 탐지 노드 자리.

tool_loop(②③④)이 모은 regulation_hits/claim_match_results 등을 violations[]로 합성하는
실제 판단 로직은 AGT-02/03에서 구현한다. 지금은 이 단계가 ⑤⑥(리라이트/HITL)과 분리된
독립 노드라는 것만 Graph에 고정한다(2026-10-20 HITL 재설계).
"""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def violation_detection_node(state: ReviewState) -> dict[str, Any]:
    return {"violations": state.get("violations", [])}
