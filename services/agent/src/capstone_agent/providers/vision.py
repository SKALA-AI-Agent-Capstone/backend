"""Vision adapter boundary for reading ad text and visual context from images and
rendered PDF pages.

docs/09-input-pipeline.md 2절: 특정 공급자/모델을 고정하지 않고 VISION_MODEL로 설정한다.
실제 호출은 아직 구현하지 않는다 (ING-03 후속 작업). 여기서는 키/모델 설정 누락을
"서버 설정 오류"로 구분하는 경계만 둔다 — 사용자 입력 오류로 섞지 않는다(같은 문서 7절).
"""

from __future__ import annotations

import os


class VisionConfigurationError(RuntimeError):
    """VISION_MODEL/키가 없을 때. 사용자 잘못이 아니라 서버 설정 오류로 분류한다."""


class VisionUnavailableError(RuntimeError):
    """공급자 429 또는 일시 장애. 제한된 재시도 대상이며, Agent Graph는 재시도 소진 시
    전체 중단한다(Notion Graph 설계의 Vision 실패 정책)."""


def vision_model() -> str:
    model = os.environ.get("VISION_MODEL", "").strip()
    if not model:
        raise VisionConfigurationError("VISION_MODEL is not configured.")
    return model


async def read_image(image_bytes: bytes, *, mime_type: str) -> dict:
    """이미지 또는 렌더된 PDF 페이지의 문자와 시각 문맥을 읽는다."""
    vision_model()
    raise NotImplementedError("Vision API call is not implemented yet (ING-03).")
