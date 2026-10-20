"""Local PDF parsing: pdfplumber for embedded text, pypdfium2 for page rendering.

docs/09-input-pipeline.md 2절의 로컬 전처리 단계만 구현한다. 문자 추출이 없는 페이지는
스캔 페이지로 간주해 NEEDS_VISION으로 표시할 뿐, Vision 호출(providers/vision.py)은
아직 여기서 수행하지 않는다.
"""

from __future__ import annotations

import io
from dataclasses import dataclass

import pdfplumber
import pypdfium2 as pdfium


@dataclass
class PdfPageText:
    page: int
    text: str
    has_text: bool


def extract_text_pages(pdf_bytes: bytes) -> list[PdfPageText]:
    """페이지별 pdfplumber 문자 추출. 빈 문자열이면 스캔 페이지로 간주한다."""
    pages: list[PdfPageText] = []
    with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
        for index, page in enumerate(pdf.pages, start=1):
            text = (page.extract_text() or "").strip()
            pages.append(PdfPageText(page=index, text=text, has_text=bool(text)))
    return pages


def render_page_png(pdf_bytes: bytes, page: int, *, scale: float = 2.0) -> bytes:
    """스캔 또는 혼합 페이지를 Vision 입력용 PNG로 렌더한다. page는 1부터 시작한다."""
    pdf = pdfium.PdfDocument(pdf_bytes)
    try:
        bitmap = pdf[page - 1].render(scale=scale)
        buffer = io.BytesIO()
        bitmap.to_pil().save(buffer, format="PNG")
        return buffer.getvalue()
    finally:
        pdf.close()
