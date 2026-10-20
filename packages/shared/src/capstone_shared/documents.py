"""Bounded PDF/image preparation. No network, business judgment or persistence."""
from dataclasses import dataclass
from io import BytesIO
import base64
import warnings

from PIL import Image, ImageOps
import pdfplumber
import pypdfium2 as pdfium

Image.MAX_IMAGE_PIXELS = 40_000_000


@dataclass
class Tile:
    id: str
    asset_id: str
    name: str
    page: int
    box: tuple[int, int, int, int]
    source_size: tuple[int, int]
    image: Image.Image

    def location(self, *, precise_box=None, method="TILE") -> dict:
        width, height = self.source_size
        x0, y0, x1, y1 = self.box
        return {"kind": "image", "fileId": self.asset_id, "fileName": self.name,
                "page": self.page, "tileId": self.id, "coordinateSource": method,
                "bbox": precise_box, "region": [x0/width, y0/height, x1/width, y1/height]}

    def data_url(self) -> str:
        # Prepared transient pixels only. Never include this in Graph State or logs.
        image = self.image.copy()
        image.thumbnail((1600, 1600))
        output = BytesIO()
        image.convert("RGB").save(output, format="JPEG", quality=92)
        return "data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode()


def tile_image(image: Image.Image, asset_id: str, name: str, page=1,
               height=1600, overlap=240) -> list[Tile]:
    if height <= overlap or overlap < 0:
        raise ValueError("INVALID_TILE_CONFIGURATION")
    image = ImageOps.exif_transpose(image).convert("RGB")
    width, total = image.size
    if width < 32 or total < 32 or width * total > 40_000_000:
        raise ValueError("IMAGE_DIMENSIONS_UNSUPPORTED")
    result = []
    for index, top in enumerate(range(0, total, height-overlap)):
        box = (0, top, width, min(top+height, total))
        result.append(Tile(f"{asset_id}:p{page}:t{index}", asset_id, name, page,
                           box, image.size, image.crop(box)))
        if top+height >= total:
            break
    if len(result) > 40:
        raise ValueError("TOO_MANY_TILES")
    return result


def prepare_document(raw: bytes, asset: dict) -> tuple[list[dict], list[Tile]]:
    if len(raw) > 10*1024*1024:
        raise ValueError("FILE_TOO_LARGE")
    blocks, tiles = [], []
    if raw.startswith(b"%PDF-"):
        with pdfplumber.open(BytesIO(raw)) as text_pdf, pdfium.PdfDocument(raw) as visual_pdf:
            if not 1 <= len(text_pdf.pages) <= 20:
                raise ValueError("PDF_PAGE_LIMIT")
            for index, page in enumerate(text_pdf.pages):
                for line_no, line in enumerate(page.extract_text_lines()):
                    text = line["text"].strip()
                    if not text:
                        continue
                    blocks.append({"id":f"{asset['id']}:p{index+1}:l{line_no}", "text":text,
                        "location":{"kind":"image", "fileId":asset["id"], "fileName":asset["name"],
                            "page":index+1, "coordinateSource":"PDF_TEXT",
                            "bbox":[line["x0"]/page.width,line["top"]/page.height,line["x1"]/page.width,line["bottom"]/page.height]}})
                pdf_page = visual_pdf[index]
                if pdf_page.get_width()*pdf_page.get_height()*4 > 40_000_000:
                    raise ValueError("PDF_PAGE_PIXELS_LIMIT")
                bitmap = pdf_page.render(scale=2)
                image = bitmap.to_pil().copy()
                bitmap.close()
                pdf_page.close()
                tiles.extend(tile_image(image,asset["id"],asset["name"],index+1))
                if len(tiles) > 40:
                    raise ValueError("TOO_MANY_TILES")
    else:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(raw)) as image:
                image.load()
                tiles = tile_image(image,asset["id"],asset["name"])
    return blocks, tiles


def local_ocr(tile: Tile) -> list[dict]:
    """Tesseract confidence belongs to this engine; it is not legal certainty."""
    import pytesseract
    data = pytesseract.image_to_data(tile.image, lang="kor+eng", timeout=20,
                                     output_type=pytesseract.Output.DICT)
    lines = {}
    for i, text in enumerate(data["text"]):
        if not text.strip():
            continue
        key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
        lines.setdefault(key, []).append(i)
    result = []
    width, height = tile.source_size
    for key, indices in lines.items():
        text = " ".join(data["text"][i] for i in indices)
        left = min(data["left"][i] for i in indices) + tile.box[0]
        top = min(data["top"][i] for i in indices) + tile.box[1]
        right = max(data["left"][i]+data["width"][i] for i in indices)+tile.box[0]
        bottom = max(data["top"][i]+data["height"][i] for i in indices)+tile.box[1]
        result.append({"id":f"{tile.id}:ocr:{key}", "text":text,
            "ocrConfidence":sum(float(data["conf"][i]) for i in indices)/len(indices),
            "location":tile.location(precise_box=[left/width,top/height,right/width,bottom/height],method="TESSERACT")})
    return result
