"""⑥ HITL 노드 자리: violation별로 원문/리라이트를 제시하고 사람이 kept/rewritten/
rewritten_manual 중 하나로 최종 결정한다.

실제 interrupt()와 checkpoint 영속화는 AGT-04에서 구현한다. "다시쓰기 요청"은 violation의
상태가 아니라 이벤트다 — 요청 횟수에 별도 guardrail은 없다(2026-10-20 HITL 재설계 확정).

사람이 rewritten_manual로 직접 문구를 써서 제출하면, final_resolution을 그 값으로
확정하기 전에 nodes/rewrite.py의 `_self_verify()`를 그 문구에 대해 1회 호출해야 한다 —
Agent가 만든 문구와 같은 프롬프트/기준이다. 이 호출 지점은 interrupt() 구현(AGT-04)과
함께 여기에 들어간다.
"""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def hitl_node(state: ReviewState) -> dict[str, Any]:
    return {"violations": state.get("violations", [])}
