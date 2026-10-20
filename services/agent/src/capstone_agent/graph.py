"""LangGraph 노드 등록.

순서는 Notion Agent Graph 초안 + 2026-10-20 HITL 재설계: ① 입력 파싱 → ②③④ 도구선택 loop
→ ② 위반 탐지 → (violations 0건이면 바로 auto_approve, 아니면 ⑤ 리라이트 자가검증 → ⑥ HITL
→ review 전체 최종 결정) → ⑦ 리포트.

violation 단위 ⑤⑥ 상태는 ReviewState.violations 안에 있다. review 전체 승인/반려/
재업로드요청은 violation 결정과 별개 층위이며 이 Graph는 그 완료 여부로 승인을 막지 않는다
(2026-10-20 HITL 재설계 5항, state/review_state.py docstring 참고).

리라이트/HITL/리포트의 실제 판단 로직은 AGT-03/04/05에서 채운다.
checkpointer는 아직 없음 — agent 스키마 Postgres checkpointer 연결은 AGT-04.
"""

from __future__ import annotations

from langgraph.graph import END, StateGraph
from langgraph.graph.state import CompiledStateGraph

from capstone_agent.nodes.auto_approve import auto_approve_node
from capstone_agent.nodes.hitl import hitl_node
from capstone_agent.nodes.input_parsing import input_parsing_node
from capstone_agent.nodes.report import report_node
from capstone_agent.nodes.review_decision import review_decision_node
from capstone_agent.nodes.rewrite import rewrite_node
from capstone_agent.nodes.tool_loop import guardrail_exceeded, tool_loop_node
from capstone_agent.nodes.violation_detection import violation_detection_node
from capstone_agent.state import ReviewState


def _loop_or_continue(state: ReviewState) -> str:
    """남은 claim이 있고 guardrail 한도 안이면 tool_loop를 반복하고, 아니면 위반 탐지로 넘어간다.

    tool_loop_node와 같은 guardrail_exceeded를 써야 한다 — 각자 다른 조건으로 판단하면
    둘 다 "아직 괜찮다"고 보는 채로 claims도 안 줄고 tool_call_count도 안 늘어나는 상태가
    반복돼 GraphRecursionError로 이어진다.

    claim 완료 여부를 더 정밀하게 판단하는 조건(증빙/특허 필요 여부 등)은 AGT-02/03에서
    보강한다. 지금은 claims 소진 또는 guardrail 한도 도달만 본다.
    """
    if guardrail_exceeded(state.get("tool_calls", [])):
        return "violation_detection"
    if not state.get("claims"):
        return "violation_detection"
    return "tool_loop"


def _route_after_violation_detection(state: ReviewState) -> str:
    """violations가 0건인 clean 케이스는 ⑤⑥을 건너뛰고 바로 자동 승인한다
    (2026-10-20 HITL 재설계 4항)."""
    if not state.get("violations"):
        return "auto_approve"
    return "rewrite"


def build_graph() -> CompiledStateGraph:
    graph = StateGraph(ReviewState)
    graph.add_node("input_parsing", input_parsing_node)
    graph.add_node("tool_loop", tool_loop_node)
    graph.add_node("violation_detection", violation_detection_node)
    graph.add_node("auto_approve", auto_approve_node)
    graph.add_node("rewrite", rewrite_node)
    graph.add_node("hitl", hitl_node)
    graph.add_node("review_decision", review_decision_node)
    graph.add_node("report", report_node)

    graph.set_entry_point("input_parsing")
    graph.add_edge("input_parsing", "tool_loop")
    graph.add_conditional_edges(
        "tool_loop",
        _loop_or_continue,
        {"tool_loop": "tool_loop", "violation_detection": "violation_detection"},
    )
    graph.add_conditional_edges(
        "violation_detection",
        _route_after_violation_detection,
        {"auto_approve": "auto_approve", "rewrite": "rewrite"},
    )
    graph.add_edge("auto_approve", "report")
    graph.add_edge("rewrite", "hitl")
    graph.add_edge("hitl", "review_decision")
    graph.add_edge("review_decision", "report")
    graph.add_edge("report", END)
    return graph.compile()
