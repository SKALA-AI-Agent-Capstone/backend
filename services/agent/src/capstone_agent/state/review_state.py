"""LangGraph State for the ad-review graph.

필드는 docs/03-contracts.md의 "Agent와 MCP의 목표 인터페이스" 표에 나온 7개 MCP 도구
(business 5개 + rag 2개)의 입력/반환 타입을 기준으로 잡았다. 그 도구들의 반환 타입
(EvidenceStructured, ClaimMatchResult, ApprovalVerifyResult, PatentVerifyResult,
PatentClaimMatchResult, RegulationHit, PrecedentHit)은 아직 packages/shared에 Pydantic
계약으로 등록되지 않았으므로, 여기서는 dict[str, Any]로 느슨하게 들고 있는다. 계약이
packages/shared에 고정되면 해당 타입으로 교체한다.

2026-10-20 HITL 재설계로 "review 전체를 사람이 한 번에 승인/반려"하던 구조를 violation
단위 3지 선택(kept/rewritten/rewritten_manual) + review 전체의 별도 최종 결정으로
바꿨다. 아래 Violation/ReviewDecision이 그 구조다. 이전의 단일 hitl_status, Finding은
이 재설계로 대체되어 제거했다.

아래는 여전히 미확정, 팀 합의 필요 (HITL 재설계와 무관한 항목):
- 광고 본문에서 탐지한 특허번호를 조회 후보에 포함할지 여부 (docs/03-contracts.md "광고 속 특허번호" 행)
- 반려/수정요청 시 재실행 방식: 새 review_id 재실행 vs 같은 건 수정안 승인 (같은 문서 "반려와 수정요청" 행).
  ReviewDecision.status의 rejected/reupload_requested는 이 재실행이 실제로 어떻게 일어나는지와는
  별개로, review의 최종 판정값만 담는다.
- 폐기된 원본의 재사용 정책, 해시 기반 버전 연결 (같은 문서 "원본 재사용" 행)
"""

from __future__ import annotations

from typing import Any, Literal, TypedDict

MCP_TOOL_NAMES = (
    "business.structure_evidence",
    "business.match_product_claims",
    "business.verify_functional_approval",
    "business.verify_patent",
    "business.match_patent_claims",
    "rag.search_regulations",
    "rag.search_precedents",
)

McpToolName = Literal[
    "business.structure_evidence",
    "business.match_product_claims",
    "business.verify_functional_approval",
    "business.verify_patent",
    "business.match_patent_claims",
    "rag.search_regulations",
    "rag.search_precedents",
]


class ToolCallRecord(TypedDict):
    # 과도기: rag.search_rules(v0.1)처럼 아직 목표 7개 도구에 없는 이름도 기록할 수 있어 str로 둔다.
    # 7개 도구가 모두 MCP에 실제 등록되면 McpToolName으로 좁힌다.
    tool: McpToolName | str
    attempt: int
    status: Literal["ok", "failed", "retrying"]


class Claim(TypedDict):
    claim_id: str
    claim_text: str
    surrounding_text: str
    source_block_id: str | None


class RewriteAttempt(TypedDict):
    attempt_no: int
    text: str
    # 사람이 "다시쓰기"를 요청하며 남긴 메모. 요청 자체는 상태가 아니라 이벤트라
    # violation에 재시도 카운트를 따로 두지 않고 이 이력에만 쌓인다(2026-10-20 HITL 재설계).
    retry_note: str | None
    created_at: str  # ISO 8601. 실제 바인딩은 노드 구현 시점(AGT-03)에 채운다.


ViolationFinalResolution = Literal["undecided", "kept", "rewritten", "rewritten_manual"]


class Violation(TypedDict, total=False):
    violation_id: str
    claim_id: str
    original_text: str
    # regulation_hits/claim_match_results 중 이 violation의 근거가 된 항목 참조.
    # 실제 연결 방식은 AGT-03.
    law_basis: list[dict[str, Any]]

    rewrite_attempts: list[RewriteAttempt]
    latest_rewrite: str | None
    self_verify_retry_count: int
    self_verify_passed: bool

    # final_resolution이 undecided가 아니게 바뀌는 순간 final_text/decided_by/decided_at이
    # 함께 채워진다. rewritten_manual은 self_verify_passed가 True여야 확정할 수 있다
    # (2026-10-20 HITL 재설계: "법적 안전성은 기계가, 나머지 가치판단은 사람이" 원칙).
    final_resolution: ViolationFinalResolution
    final_text: str | None
    manual_text: str | None
    decided_by: str | None
    decided_at: str | None


ReviewDecisionStatus = Literal["pending", "approved", "rejected", "reupload_requested"]


class ReviewDecision(TypedDict, total=False):
    status: ReviewDecisionStatus
    reason: str | None
    # violations가 0건이라 Agent가 자동 승인했으면 decided_by/decided_at은 비워 "사람이
    # 아니라 시스템이 승인했다"는 걸 구분한다(2026-10-20 HITL 재설계 4항).
    decided_by: str | None
    decided_at: str | None


def all_violations_decided(violations: list[Violation]) -> bool:
    """review 전체의 "모든 violation이 결정됐는지"는 별도 필드로 캐싱하지 않고 그때그때
    계산한다 — 저장해뒀다가 violations 갱신 때 동기화를 깜빡하면 바로 버그가 되는 값이다
    (2026-10-20 HITL 재설계 5항). review 승인 가능 여부를 이 값으로 막는 건 Graph가 아니라
    화면/애플리케이션의 책임이며, 여기서는 판단 자료만 제공한다.
    """
    return all(v.get("final_resolution", "undecided") != "undecided" for v in violations)


class ReviewState(TypedDict, total=False):
    # 인증된 실행 문맥. 모델이 값을 선택하지 않고 호출 시점에 바인딩한다.
    tenant_id: str
    review_id: str

    # ① 입력 파싱. ad_assets는 input_parsing_node가 소비한 뒤 바로 비우는 임시 입력이며
    # 원본 bytes를 checkpoint에 남기지 않는다(docs/09-input-pipeline.md 8절).
    # ad_blocks는 그 결과(SourceBlock 제안, 아직 미확정 계약, 같은 문서 6절).
    ad_assets: list[dict[str, Any]]
    ad_blocks: list[dict[str, Any]]
    evidence_documents: list[dict[str, Any]]

    # ②③④ 도구선택 loop
    claims: list[Claim]
    tool_calls: list[ToolCallRecord]
    tool_call_count: int
    loop_started_at: float | None

    evidence_structured: dict[str, Any] | None
    claim_match_results: list[dict[str, Any]]
    approval_verify_result: dict[str, Any] | None
    patent_verify_results: list[dict[str, Any]]
    patent_claim_match_result: dict[str, Any] | None
    regulation_hits: list[dict[str, Any]]
    precedent_hits: list[dict[str, Any]]

    # ② 위반 탐지. ⑤(자가검증 리라이트)와 ⑥(HITL 3지 선택)의 모든 상태는 violation 단위로
    # Violation 안에 있다 (2026-10-20 HITL 재설계).
    violations: list[Violation]

    # review 전체의 최종 결정. all_violations_decided()로 "모두 결정됐는지"를 그때그때
    # 계산할 뿐, 이 Graph는 그 값으로 승인을 막지 않는다(2026-10-20 HITL 재설계 5항).
    review_decision: ReviewDecision

    # ⑦ 리포트
    report_url: str | None
    report_retry_count: int

    errors: list[str]
