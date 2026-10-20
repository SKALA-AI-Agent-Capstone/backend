from datetime import date
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from capstone_shared import CONTRACT_VERSION


class SearchStatus(StrEnum):
    FOUND = "FOUND"
    NO_RELEVANT_EVIDENCE = "NO_RELEVANT_EVIDENCE"
    NEEDS_CONTEXT = "NEEDS_CONTEXT"
    SEARCH_ERROR = "SEARCH_ERROR"
    NOT_IMPLEMENTED = "NOT_IMPLEMENTED"


class SearchCollection(StrEnum):
    RULES = "rules"
    CASES = "cases"


class SearchRulesInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    claim_id: str = Field(min_length=1, max_length=100)
    claim_text: str = Field(min_length=1, max_length=3000)
    surrounding_text: str = Field(default="", max_length=8000)
    product_type: str | None = Field(default=None, max_length=100)
    jurisdiction: str = Field(default="KR", pattern=r"^[A-Z]{2}$")
    review_date: date
    top_k: int = Field(default=5, ge=1, le=10)


class Evidence(BaseModel):
    evidence_id: str
    document_version_id: str
    source_type: str
    source_verified: bool = False
    document_title: str
    document_version: str
    section: str | None = None
    page: int | None = Field(default=None, ge=1)
    quote: str
    context: str
    source_url: str


class SearchRulesResult(BaseModel):
    contract_version: str = CONTRACT_VERSION
    claim_id: str
    collection: SearchCollection = SearchCollection.RULES
    status: SearchStatus
    corpus_version: str | None = None
    evidence: list[Evidence] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class ScaffoldResult(BaseModel):
    contract_version: str = CONTRACT_VERSION
    status: str = "NOT_IMPLEMENTED"
    message: str
