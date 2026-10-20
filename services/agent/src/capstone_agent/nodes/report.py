"""⑦ 리포트 노드: review_decision.decided_by가 없으면(violations 0건, 시스템 자동 승인)
그 사실을 리포트 문구에서 명확히 구분한다 — "검토자가 확인하여 승인했다"는 톤을
자동승인 케이스에 쓰지 않는다(2026-10-20 HITL 재설계 원칙: 법적 안전성은 기계가, 나머지
가치판단은 사람이 책임진다).

승인 주체(기계/사람) 판정은 결정적 로직(_approval_mode)으로 코드에 두고, LLM은 그 결과를
받아 mode별로 다른 문체의 문장만 생성한다 — LLM이 "누가 승인했는지"를 스스로 추론하게
두지 않는다.

실제 LLM 호출(providers/text.py)과 PDF 렌더(docs/01-architecture.md의 WeasyPrint 후보)는
아직 구현하지 않았다(AGT-05 후속). 재시도 한도는 REPORT_GENERATION_RETRY_LIMIT.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel

from capstone_agent.providers.text import complete_structured
from capstone_agent.state import ReviewState

REPORT_SYSTEM_PROMPT = """\
너는 화장품 광고 검토 리포트의 승인 경위를 한 문단으로 요약하는 보조자다. 주어진
approval_mode를 그대로 받아들이고, 승인 주체가 누구인지 스스로 다시 판단하지 않는다.

approval_mode에 따라 문체를 분기한다:
- system_auto_approved: 위반이 0건이라 시스템이 자동으로 승인했다는 사실을 그대로 써라.
  "검토자가 확인했다", "담당자가 승인했다" 같은 사람의 검토를 암시하는 표현을 쓰지 않는다.
- human_approved: 담당자가 각 위반의 처리 결과(원문 유지/리라이트 채택/직접 수정)를
  확인하고 승인했다는 사실을 써라. 위반 중 원문을 그대로 둔 항목이 있으면 그 점도 숨기지
  말고 언급해라.
- human_rejected / human_reupload_requested: 담당자가 반려했거나 원본 재업로드를
  요청했다는 사실과 그 사유(reason)를 써라.

실제로 결정한 주체(기계/사람)를 과장하거나 흐리지 않는다. 이건 책임 소재를 가리는
문장이다.
"""

ApprovalMode = Literal[
    "system_auto_approved", "human_approved", "human_rejected", "human_reupload_requested"
]


class ReportApprovalSummary(BaseModel):
    approval_mode: ApprovalMode
    approval_statement: str


def _approval_mode(review_decision: dict[str, Any]) -> ApprovalMode:
    status = review_decision.get("status")
    if status == "approved":
        return "system_auto_approved" if review_decision.get("decided_by") is None else "human_approved"
    if status == "reupload_requested":
        return "human_reupload_requested"
    return "human_rejected"


async def report_node(state: ReviewState) -> dict[str, Any]:
    review_decision = state.get("review_decision") or {}
    try:
        summary = await complete_structured(
            REPORT_SYSTEM_PROMPT,
            {
                "approval_mode": _approval_mode(review_decision),
                "review_decision": review_decision,
                "violations": state.get("violations", []),
            },
            output_schema=ReportApprovalSummary,
        )
    except NotImplementedError:
        return {"report_url": state.get("report_url")}

    return {
        "report_url": state.get("report_url"),
        "report_approval_statement": summary.approval_statement,
    }
