"""Text LLM adapter boundary for the tool-selection loop, self-verify rewrite loop, and
report generation.

docs/09-input-pipeline.md 2절: 특정 공급자/모델을 고정하지 않고 TEXT_MODEL로 설정한다.
providers/vision.py와 같은 경계 원칙을 쓴다: 키/모델 설정 누락은 서버 설정 오류로,
실제 호출 미구현은 NotImplementedError로 구분한다. 실제 호출은 AGT-02/03/05에서 구현한다.
"""

from __future__ import annotations

import os
from typing import Any, TypeVar

from pydantic import BaseModel

SchemaT = TypeVar("SchemaT", bound=BaseModel)


class TextModelConfigurationError(RuntimeError):
    """TEXT_MODEL/키가 없을 때. 사용자 잘못이 아니라 서버 설정 오류로 분류한다."""


class TextModelUnavailableError(RuntimeError):
    """공급자 429 또는 일시 장애. 제한된 재시도 대상."""


def text_model() -> str:
    model = os.environ.get("TEXT_MODEL", "").strip()
    if not model:
        raise TextModelConfigurationError("TEXT_MODEL is not configured.")
    return model


async def complete_structured(
    system_prompt: str, user_input: Any, *, output_schema: type[SchemaT]
) -> SchemaT:
    """system_prompt + user_input을 주고 output_schema에 맞는 구조화 출력을 받는다."""
    text_model()
    raise NotImplementedError("Text LLM call is not implemented yet.")
