"""②③④ 도구선택 loop 노드.

목표 7개 도구(docs/03-contracts.md) 중 지금 MCP에 실제로 떠 있는 건 rag-mcp의 v0.1
rag.search_rules뿐이다(business 5개, rag.search_regulations/search_precedents는
NOT_IMPLEMENTED). 그래서 지금은 claim마다 search_rules를 호출해 배관만 연결하고,
guardrail(총 호출 수, 동일 tool 재호출 수, loop 시간 제한)을 그대로 적용한다.
목표 도구가 실제로 올라오면 claim 성격에 따른 도구 선택 분기를 AGT-02/03에서 채운다.
"""

from __future__ import annotations

import time
from typing import Any

from capstone_agent.providers.mcp_client import call_rag_tool
from capstone_agent.state import (
    LOOP_TIMEOUT_SECONDS,
    MAX_SAME_TOOL_CALLS,
    MAX_TOOL_CALLS_TOTAL,
    ReviewState,
)

_TRANSITIONAL_TOOL = "rag.search_rules"


def guardrail_exceeded(tool_calls: list[dict[str, Any]]) -> bool:
    """tool_loop_node와 graph._loop_or_continue가 같은 한도 판단을 쓰도록 공유한다.

    둘이 각자 판단하면 한쪽은 '아직 괜찮다'고 보고 loop로 되돌리고 다른 쪽은 멈춰야 한다고
    보는 불일치가 생겨 claims도 안 줄고 tool_call_count도 안 늘어나는 채로 tool_loop만
    반복해 GraphRecursionError로 이어진다.
    """
    same_tool_calls = sum(1 for call in tool_calls if call["tool"] == _TRANSITIONAL_TOOL)
    return len(tool_calls) >= MAX_TOOL_CALLS_TOTAL or same_tool_calls >= MAX_SAME_TOOL_CALLS


async def tool_loop_node(state: ReviewState) -> dict[str, Any]:
    claims = list(state.get("claims", []))
    tool_calls = list(state.get("tool_calls", []))
    started_at = state.get("loop_started_at") or time.monotonic()
    errors = list(state.get("errors", []))

    if time.monotonic() - started_at > LOOP_TIMEOUT_SECONDS:
        errors.append("LOOP_TIMEOUT")
        return {"loop_started_at": started_at, "errors": errors}

    if guardrail_exceeded(tool_calls) or not claims:
        return {"loop_started_at": started_at}

    claim, *remaining_claims = claims

    try:
        result = await call_rag_tool(
            "search_rules",
            {
                "query": {
                    "claim_id": claim["claim_id"],
                    "claim_text": claim["claim_text"],
                    "surrounding_text": claim.get("surrounding_text", ""),
                }
            },
        )
        tool_calls.append({"tool": _TRANSITIONAL_TOOL, "attempt": 1, "status": "ok"})
        return {
            "claims": remaining_claims,
            "tool_calls": tool_calls,
            "tool_call_count": state.get("tool_call_count", 0) + 1,
            "loop_started_at": started_at,
            "regulation_hits": state.get("regulation_hits", []) + [result],
        }
    except Exception as exc:  # 개별 tool 실패 2회 재시도 정책은 AGT-02에서 구현한다.
        tool_calls.append({"tool": _TRANSITIONAL_TOOL, "attempt": 1, "status": "failed"})
        errors.append(f"TOOL_CALL_FAILED:{_TRANSITIONAL_TOOL}:{exc}")
        return {
            "claims": remaining_claims,
            "tool_calls": tool_calls,
            "loop_started_at": started_at,
            "errors": errors,
        }
