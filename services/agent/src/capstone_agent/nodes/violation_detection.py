"""② 위반 탐지 노드.

tool_loop(②③④)이 모은 claims/regulation_hits/claim_match_results를 바탕으로, claim마다
실제 위반인지와 그 근거(law_basis)를 LLM이 구조화해서 뱉게 한다. 이 출력이 그대로
app.violation 테이블의 text_excerpt/law_basis 입력이 된다.

실제 LLM 호출(providers/text.py)은 아직 구현하지 않았다 — TEXT_MODEL 어댑터가 없다
(AGT-02 후속). 호출이 안 되면 violations를 그대로 둔다.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from capstone_agent.nodes.schemas import LawBasisCitation
from capstone_agent.providers.text import complete_structured
from capstone_agent.state import ReviewState

VIOLATION_DETECTION_SYSTEM_PROMPT = """\
너는 화장품 광고 심사 보조자다. 지금까지 모은 claim과 그 claim에 대해 검색된 법령/기준
근거(regulation_hits), 제품·증빙 대조 결과(claim_match_results)를 보고, 각 claim이 실제로
광고 규정을 위반하는지 판단한다.

규칙:
- 위반이라고 확정할 수 있는 근거가 없으면 is_violation을 false로 두어라. 근거 부족을
  위반으로 단정하지 않는다.
- 위반이면 text_excerpt에 광고 원문에서 문제가 되는 부분을 그대로 발췌해라. 쉬운 설명으로
  바꿔 쓰지 마라 — 원문 인용과 네 설명을 섞지 않는다.
- law_basis에는 실제로 이 판단에 쓴 근거만 담아라. regulation_hits에 있었다는 이유만으로
  관련 없는 항목을 끌어오지 않는다.
- 하나의 claim에 여러 근거가 겹치면 전부 law_basis에 나열해라. 가장 강한 근거 하나만
  고르지 않는다.
- 같은 claim이 여러 번 나오면 하나의 violation으로 합쳐라. 중복 violation을 만들지 않는다.
"""


class DetectedViolation(BaseModel):
    claim_id: str
    is_violation: bool
    text_excerpt: str | None = None
    law_basis: list[LawBasisCitation] = Field(default_factory=list)


class ViolationDetectionOutput(BaseModel):
    violations: list[DetectedViolation]


async def violation_detection_node(state: ReviewState) -> dict[str, Any]:
    try:
        result = await complete_structured(
            VIOLATION_DETECTION_SYSTEM_PROMPT,
            {
                "claims": state.get("claims", []),
                "regulation_hits": state.get("regulation_hits", []),
                "claim_match_results": state.get("claim_match_results", []),
            },
            output_schema=ViolationDetectionOutput,
        )
    except NotImplementedError:
        return {"violations": state.get("violations", [])}

    violations = [
        {
            "violation_id": f"{state.get('review_id', 'review')}-{detected.claim_id}",
            "claim_id": detected.claim_id,
            "original_text": detected.text_excerpt,
            "law_basis": [basis.model_dump() for basis in detected.law_basis],
            "rewrite_attempts": [],
            "latest_rewrite": None,
            "self_verify_retry_count": 0,
            "self_verify_passed": False,
            "final_resolution": "undecided",
            "final_text": None,
            "manual_text": None,
            "decided_by": None,
            "decided_at": None,
        }
        for detected in result.violations
        if detected.is_violation
    ]
    return {"violations": violations}
