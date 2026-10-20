"""⑤ 리라이트 노드 자리: violation마다 자동 리라이트를 생성하고 자가검증할 때까지 반복한다.

실제 리라이트 생성과 재검증(law_basis 재탐지 등)은 AGT-03에서 구현한다. 지금은
REWRITE_SELF_VERIFY_RETRY_LIMIT guardrail과 RewriteAttempt 이력 구조만 Graph에 고정한다.
"다시쓰기 요청"은 violation의 상태가 아니라 이벤트다 — 사람이 요청하면 이 노드가 새
RewriteAttempt를 쌓을 뿐 final_resolution은 undecided로 남는다(2026-10-20 HITL 재설계).
"""

from __future__ import annotations

from typing import Any

from capstone_agent.state import ReviewState


async def rewrite_node(state: ReviewState) -> dict[str, Any]:
    # 실제 생성/재검증 호출이 아직 없어 pass-through다. AGT-03 구현 시 self_verify_retry_count가
    # REWRITE_SELF_VERIFY_RETRY_LIMIT에 도달한 violation은 더 이상 자동 재시도하지 않고
    # 사람의 결정(⑥)으로 넘긴다.
    return {"violations": state.get("violations", [])}
