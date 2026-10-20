"""LangGraph 노드 등록.

순서는 Notion Agent Graph 초안: ① 입력 파싱 → ②③④ 도구선택 loop → ⑤ 리라이트 →
⑥ HITL → ⑦ 리포트. 리라이트/HITL/리포트의 실제 판단 로직은 AGT-03/04/05에서 채운다.
checkpointer는 아직 없음 — agent 스키마 Postgres checkpointer 연결은 AGT-04.
"""

from __future__ import annotations

from langgraph.graph import END, StateGraph
from langgraph.graph.state import CompiledStateGraph

from capstone_agent.nodes.hitl import hitl_node
from capstone_agent.nodes.input_parsing import input_parsing_node
from capstone_agent.nodes.report import report_node
from capstone_agent.nodes.rewrite import rewrite_node
from capstone_agent.nodes.tool_loop import guardrail_exceeded, tool_loop_node
from capstone_agent.state import ReviewState


def _loop_or_continue(state: ReviewState) -> str:
    """남은 claim이 있고 guardrail 한도 안이면 loop를 반복한다.

    tool_loop_node와 같은 guardrail_exceeded를 써야 한다 — 각자 다른 조건으로 판단하면
    둘 다 "아직 괜찮다"고 보는 채로 claims도 안 줄고 tool_call_count도 안 늘어나는 상태가
    반복돼 GraphRecursionError로 이어진다.

    claim 완료 여부를 더 정밀하게 판단하는 조건(증빙/특허 필요 여부 등)은 AGT-02/03에서
    보강한다. 지금은 claims 소진 또는 guardrail 한도 도달만 본다.
    """
    if guardrail_exceeded(state.get("tool_calls", [])):
        return "rewrite"
    if not state.get("claims"):
        return "rewrite"
    return "tool_loop"


def build_graph() -> CompiledStateGraph:
    graph = StateGraph(ReviewState)
    graph.add_node("input_parsing", input_parsing_node)
    graph.add_node("tool_loop", tool_loop_node)
    graph.add_node("rewrite", rewrite_node)
    graph.add_node("hitl", hitl_node)
    graph.add_node("report", report_node)

    graph.set_entry_point("input_parsing")
    graph.add_edge("input_parsing", "tool_loop")
    graph.add_conditional_edges(
        "tool_loop", _loop_or_continue, {"tool_loop": "tool_loop", "rewrite": "rewrite"}
    )
    graph.add_edge("rewrite", "hitl")
    graph.add_edge("hitl", "report")
    graph.add_edge("report", END)
    return graph.compile()
