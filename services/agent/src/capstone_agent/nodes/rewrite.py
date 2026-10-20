"""⑤ 리라이트 노드: violation마다 자동 리라이트를 생성하고, 자가검증을 통과할 때까지
반복한다(REWRITE_SELF_VERIFY_RETRY_LIMIT까지).

리라이트 생성과 자가검증은 역할이 다른 두 개의 별도 LLM 호출이다 — 하나의 프롬프트에
섞지 않는다. 자가검증 프롬프트는 입력이 Agent가 만든 문구든 사람이 쓴 rewritten_manual
문구든 같은 기준으로 판단해야 한다 — final_resolution을 rewritten_manual로 확정하려면
이 자가검증을 반드시 1회 통과해야 하기 때문이다(2026-10-20 HITL 재설계).

실제 LLM 호출(providers/text.py)은 아직 구현하지 않았다(AGT-03 후속). 호출이 안 되면
violation을 그대로 두고 다음 그래프 실행에서 다시 시도한다 — 가짜 시도를 기록하지 않는다.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from capstone_agent.nodes.schemas import LawBasisCitation
from capstone_agent.providers.text import complete_structured
from capstone_agent.state import REWRITE_SELF_VERIFY_RETRY_LIMIT, ReviewState

REWRITE_SYSTEM_PROMPT = """\
너는 화장품 광고 문구를 법적으로 안전하게 고치는 보조자다. 아래 두 목표를 동시에 추구한다:

1. 법적 안전성 확보: law_basis에 명시된 근거를 더 이상 위반하지 않도록 고친다. 이 목표가
   2번과 충돌하면 항상 이 목표를 우선한다.
2. 마케팅 설득력/톤 유지: 원문의 임팩트, 강조 표현, 어조를 법적으로 문제되지 않는 한
   최대한 그대로 둔다. 법적 안전성과 무관한 표현을 불필요하게 깎아내지 않는다.

이전 시도(rewrite_attempts)가 있으면 그 결과와 사람이 남긴 재시도 메모(retry_note)를
반영해라. law_basis를 피하는 데 실패했던 이전 표현을 그대로 반복하지 않는다.
"""

SELF_VERIFY_SYSTEM_PROMPT = """\
너는 화장품 광고 문구가 법적으로 안전한지만 판단하는 검증자다. 문구를 새로 쓰지 않는다.

입력된 문구가 Agent가 생성한 제안인지 사람이 직접 쓴 문구인지는 구분하지 않고 완전히
같은 기준으로 판단한다. law_basis에 열거된 근거 각각에 대해 지금도 위반인지 다시
확인하고, 여전히 걸리는 근거만 triggered_law_basis에 남겨라. 하나도 안 걸리면
self_verify_passed를 true로, triggered_law_basis를 빈 리스트로 둔다.
"""


class RewriteGenerationOutput(BaseModel):
    rewritten_text: str
    rationale: str


class SelfVerifyOutput(BaseModel):
    self_verify_passed: bool
    triggered_law_basis: list[LawBasisCitation] = Field(default_factory=list)
    reasoning: str


async def _generate_rewrite(violation: dict[str, Any]) -> RewriteGenerationOutput:
    return await complete_structured(
        REWRITE_SYSTEM_PROMPT,
        {
            "original_text": violation.get("original_text"),
            "law_basis": violation.get("law_basis", []),
            "rewrite_attempts": violation.get("rewrite_attempts", []),
        },
        output_schema=RewriteGenerationOutput,
    )


async def _self_verify(text: str, law_basis: list[dict[str, Any]]) -> SelfVerifyOutput:
    return await complete_structured(
        SELF_VERIFY_SYSTEM_PROMPT,
        {"text": text, "law_basis": law_basis},
        output_schema=SelfVerifyOutput,
    )


async def rewrite_node(state: ReviewState) -> dict[str, Any]:
    violations = list(state.get("violations", []))

    for violation in violations:
        if violation.get("final_resolution", "undecided") != "undecided":
            continue
        if violation.get("self_verify_passed"):
            continue
        if violation.get("self_verify_retry_count", 0) >= REWRITE_SELF_VERIFY_RETRY_LIMIT:
            continue

        try:
            generated = await _generate_rewrite(violation)
            verified = await _self_verify(generated.rewritten_text, violation.get("law_basis", []))
        except NotImplementedError:
            # TEXT_MODEL 어댑터가 아직 없다. 가짜 시도를 기록하지 않고 그대로 둔다(AGT-03 후속).
            continue

        attempt_no = len(violation.get("rewrite_attempts", [])) + 1
        violation["rewrite_attempts"] = violation.get("rewrite_attempts", []) + [
            {
                "attempt_no": attempt_no,
                "text": generated.rewritten_text,
                "retry_note": None,
                "created_at": "",
            }
        ]
        violation["latest_rewrite"] = generated.rewritten_text
        violation["self_verify_retry_count"] = violation.get("self_verify_retry_count", 0) + 1
        violation["self_verify_passed"] = verified.self_verify_passed

    return {"violations": violations}
