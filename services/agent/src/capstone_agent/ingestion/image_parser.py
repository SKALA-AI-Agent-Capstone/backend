"""Local image decoding and tiling for long detail-page images.

docs/09-input-pipeline.md 2절: 세로로 긴 이미지는 겹치는 타일로 분할하고 원본 좌표를 기록한다.
"""

from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image

# ponytail: 고정 타일 크기. 실제 광고 이미지 해상도 분포로 평가 전까지의 임시값.
MAX_TILE_HEIGHT = 2000
TILE_OVERLAP = 200


@dataclass
class ImageTile:
    index: int
    top: int
    bottom: int
    png_bytes: bytes


def decode_image(image_bytes: bytes) -> Image.Image:
    return Image.open(io.BytesIO(image_bytes)).convert("RGB")


def tile_long_image(image: Image.Image) -> list[ImageTile]:
    """높이가 MAX_TILE_HEIGHT를 넘으면 겹치는 타일로 나눈다. 짧은 이미지는 타일 1개."""
    width, height = image.size
    if height <= MAX_TILE_HEIGHT:
        return [_crop_tile(image, 0, height, index=0)]

    tiles: list[ImageTile] = []
    top = 0
    index = 0
    while top < height:
        bottom = min(top + MAX_TILE_HEIGHT, height)
        tiles.append(_crop_tile(image, top, bottom, index=index))
        if bottom == height:
            break
        top = bottom - TILE_OVERLAP
        index += 1
    return tiles


def _crop_tile(image: Image.Image, top: int, bottom: int, *, index: int) -> ImageTile:
    cropped = image.crop((0, top, image.width, bottom))
    buffer = io.BytesIO()
    cropped.save(buffer, format="PNG")
    return ImageTile(index=index, top=top, bottom=bottom, png_bytes=buffer.getvalue())
