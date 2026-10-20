"""⑦ 리포트 노드 자리. 실제 생성과 재시도 3회 정책은 AGT-05에서 구현한다."""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def report_node(state: ReviewState) -> dict[str, Any]:
    return {"report_url": state.get("report_url")}
