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
