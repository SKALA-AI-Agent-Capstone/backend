"""LLM 구조화 출력 스키마에서 공유하는 조각.

ReviewState의 TypedDict와는 다른 층위다 — 이건 LLM에게 "이 모양으로 응답해라"라고
강제하는 계약이고, 노드가 그 결과를 Violation 같은 State 필드로 옮겨 담는다.
packages/shared의 서비스 간 공유 계약과도 다르다 — 여긴 agent 내부에서만 쓰는
LLM 출력 계약이라 공유 변경 합의 대상이 아니다.
"""

from __future__ import annotations

from pydantic import BaseModel


class LawBasisCitation(BaseModel):
    """rag.search_regulations/search_precedents 결과 중 인용한 근거 한 줄.
    docs/03-contracts.md의 RegulationHit/PrecedentHit 필드와 의미를 맞춘다."""

    source: str
    version: str
    scope: str
    excerpt: str
    similarity: float
