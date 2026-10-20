from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class AssetRef(BaseModel):
    model_config = ConfigDict(extra="ignore")
    id: str
    name: str
    role: Literal["advertisement", "evidence"]
    docType: str = ""


class RunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reviewId: str
    tenantId: str
    versionNo: int = Field(ge=1)
    runToken: str = Field(min_length=32, repr=False)
    product: dict
    assets: list[AssetRef] = Field(max_length=15)


class OCRLines(BaseModel):
    lines: list[str]
    unreadable: bool


class TextIssue(BaseModel):
    phrase: str
    reason: str
    claimType: str
    rewrites: list[str]


class TextAssessment(BaseModel):
    findings: list[TextIssue]


class VisualObservation(BaseModel):
    observation: str
    concern: str
    comparisonLimitations: list[str]
    needsAdjacentContext: bool


class VisualAssessment(BaseModel):
    findings: list[VisualObservation]
    unreadable: bool


class ResumeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reviewId: str
    tenantId: str
    versionNo: int = Field(ge=1)
    state: Literal["approved", "rejected", "changes_requested"]


class RewriteRequest(BaseModel):
    """Backend가 사람의 "다시쓰기" 요청을 Agent에 전달하는 입력. 한도와 저장은 Backend가 맡는다."""
    model_config = ConfigDict(extra="forbid")
    reviewId: str
    tenantId: str
    phrase: str = Field(min_length=1, max_length=2000)
    reason: str = Field(default="", max_length=4000)
    claimType: str = Field(default="", max_length=100)
    previousRewrites: list[str] = Field(default_factory=list, max_length=50)
    retryNote: str | None = Field(default=None, max_length=1000)


class RewriteSuggestion(BaseModel):
    text: str


class VerifyRewriteRequest(BaseModel):
    """Backend가 rewritten_manual로 확정하기 전에 사람이 쓴 문구를 검증받는 입력.
    app.violations.final_text_validated=true가 되려면 이 호출이 먼저 통과해야 한다."""
    model_config = ConfigDict(extra="forbid")
    reviewId: str
    tenantId: str
    text: str = Field(min_length=1, max_length=2000)
    reason: str = Field(default="", max_length=4000)


class RewriteVerification(BaseModel):
    passed: bool
    reasoning: str
