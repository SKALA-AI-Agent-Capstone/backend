"""⑥ HITL 노드 자리. interrupt()와 checkpoint 영속화는 AGT-04에서 구현한다.

hitl_status의 상태 집합은 docs/03-contracts.md "승인 대기" 행에서 미확정이라
여기서 임의로 고정하지 않는다.
"""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def hitl_node(state: ReviewState) -> dict[str, Any]:
    return {"hitl_status": state.get("hitl_status")}
