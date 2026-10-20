"""Notion target RAG tools: search_regulations and search_precedents.

The Notion draft returns a bare list. These envelopes add a status so an empty
corpus or a database failure is never read as "no violation found".
"""
from datetime import date
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field

from capstone_shared import CONTRACT_VERSION


class RetrievalStatus(StrEnum):
    FOUND = "FOUND"
    NO_RELEVANT_EVIDENCE = "NO_RELEVANT_EVIDENCE"
    CORPUS_NOT_INDEXED = "CORPUS_NOT_INDEXED"
    SEARCH_ERROR = "SEARCH_ERROR"


class RetrievalMode(StrEnum):
    KEYWORD = "keyword"
    HYBRID = "hybrid"


class RegulationHit(BaseModel):
    # Notion RegulationHit fields
    source: str
    version: str
    scope: str
    excerpt: str
    similarity: float = Field(ge=0, le=1, description="Retrieval score, not a violation probability.")
    trust_level: Literal["high"] = "high"
    # Traceability for the report (NFR-11)
    document_key: str
    section: str | None = None
    page: int | None = Field(default=None, ge=1)
    source_url: str
    acquisition: str
    effective_from: date | None = None
    effective_until: date | None = None


class PrecedentHit(BaseModel):
    source: str
    excerpt: str
    similarity: float = Field(ge=0, le=1)
    trust_level: Literal["low"] = "low"
    verified: Literal[False] = False
    document_key: str
    page: int | None = Field(default=None, ge=1)
    source_url: str


class RegulationSearchResult(BaseModel):
    contract_version: str = CONTRACT_VERSION
    status: RetrievalStatus
    review_date: date
    retrieval_mode: RetrievalMode | None = None
    corpus_versions: list[str] = Field(default_factory=list)
    hits: list[RegulationHit] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class PrecedentSearchResult(BaseModel):
    contract_version: str = CONTRACT_VERSION
    status: RetrievalStatus
    retrieval_mode: RetrievalMode | None = None
    corpus_versions: list[str] = Field(default_factory=list)
    hits: list[PrecedentHit] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
