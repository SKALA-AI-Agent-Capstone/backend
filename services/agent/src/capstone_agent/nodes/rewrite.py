"""⑤ 리라이트 노드 자리. 근거 종합과 문구 제안은 AGT-03에서 구현한다."""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def rewrite_node(state: ReviewState) -> dict[str, Any]:
    return {"findings": state.get("findings", [])}
