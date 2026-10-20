"""LangGraph State for the ad-review graph.

필드는 docs/03-contracts.md의 "Agent와 MCP의 목표 인터페이스" 표에 나온 7개 MCP 도구
(business 5개 + rag 2개)의 입력/반환 타입을 기준으로 잡았다. 그 도구들의 반환 타입
(EvidenceStructured, ClaimMatchResult, ApprovalVerifyResult, PatentVerifyResult,
PatentClaimMatchResult, RegulationHit, PrecedentHit)은 아직 packages/shared에 Pydantic
계약으로 등록되지 않았으므로, 여기서는 dict[str, Any]로 느슨하게 들고 있는다. 계약이
packages/shared에 고정되면 해당 타입으로 교체한다.

아래는 미확정, 팀 합의 필요:
- hitl_status의 정확한 상태 집합과 화면 표시 방식 (docs/03-contracts.md "승인 대기" 행)
- 광고 본문에서 탐지한 특허번호를 조회 후보에 포함할지 여부 (같은 문서 "광고 속 특허번호" 행)
- 반려/수정요청 시 재실행 방식: 새 review_id 재실행 vs 같은 건 수정안 승인 (같은 문서 "반려와 수정요청" 행)
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


class Finding(TypedDict, total=False):
    claim_id: str
    verdict: str
    rewrite_suggestion: str | None
    citations: list[dict[str, Any]]
    insufficient_evidence: bool


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

    # ⑤ 리라이트
    findings: list[Finding]

    # ⑥ HITL interrupt. 상태 집합은 미확정(docs/03-contracts.md 참고).
    hitl_status: str | None

    # ⑦ 리포트
    report_url: str | None
    report_retry_count: int

    errors: list[str]
