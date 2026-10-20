"""① 입력 파싱 노드: 광고 PDF/이미지를 로컬 파서로 블록화한다.

Vision 호출(판독과 시각 문맥)은 providers/vision.py에 자리만 두었고 아직 구현하지 않았다.
문자 추출이 없는 페이지/이미지는 NEEDS_VISION으로만 표시하고 판독 완료로 단정하지 않는다
(docs/09-input-pipeline.md 5절). 원본 bytes는 파싱 직후 state에서 비운다(같은 문서 8절).
"""

from __future__ import annotations

from typing import Any

from capstone_agent.ingestion.image_parser import decode_image, tile_long_image
from capstone_agent.ingestion.pdf_parser import extract_text_pages
from capstone_agent.state import ReviewState

_SUPPORTED_IMAGE_TYPES = {"image/png", "image/jpeg"}


async def input_parsing_node(state: ReviewState) -> dict[str, Any]:
    ad_blocks: list[dict[str, Any]] = list(state.get("ad_blocks", []))
    errors: list[str] = list(state.get("errors", []))

    for asset in state.get("ad_assets", []):
        content: bytes = asset["content"]
        asset_id: str = asset["asset_id"]
        asset_role: str = asset.get("asset_role", "ADVERTISEMENT")
        mime_type: str = asset["mime_type"]

        if mime_type == "application/pdf":
            for page in extract_text_pages(content):
                ad_blocks.append(
                    {
                        "asset_id": asset_id,
                        "asset_role": asset_role,
                        "page": page.page,
                        "text": page.text,
                        "extraction_method": "PDFPLUMBER" if page.has_text else "NEEDS_VISION",
                        "quality_status": "OK" if page.has_text else "NEEDS_CONFIRMATION",
                    }
                )
        elif mime_type in _SUPPORTED_IMAGE_TYPES:
            image = decode_image(content)
            for tile in tile_long_image(image):
                ad_blocks.append(
                    {
                        "asset_id": asset_id,
                        "asset_role": asset_role,
                        "page": tile.index,
                        "text": "",
                        "extraction_method": "NEEDS_VISION",
                        "quality_status": "NEEDS_CONFIRMATION",
                    }
                )
        else:
            errors.append(f"INPUT_REJECTED: unsupported mime_type {mime_type}")

    # ad_assets는 원본 bytes를 담고 있으므로 파싱 후 즉시 비운다.
    return {"ad_assets": [], "ad_blocks": ad_blocks, "errors": errors}
