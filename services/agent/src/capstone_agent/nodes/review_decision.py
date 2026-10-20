"""review 전체의 최종 결정(승인/반려/원본재업로드요청) 노드 자리.

violation별 결정(⑥)과는 별개 층위다 — all_violations_decided()가 False여도 이 Graph는
승인을 막지 않는다. violation을 전부 "원문 유지"로 두고 review를 승인하는 것도 허용되는
사람의 업무 판단이며, 승인 전 경고를 보여줄지는 화면의 책임이다(2026-10-20 HITL 재설계 5항).
실제 승인/반려 interrupt와 checkpoint는 AGT-04에서 구현한다.
"""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def review_decision_node(state: ReviewState) -> dict[str, Any]:
    return {"review_decision": state.get("review_decision")}
