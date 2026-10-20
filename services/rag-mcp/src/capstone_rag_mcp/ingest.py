"""Write public source documents into knowledge.* as rag_ingest.

An existing (document_key, version_label, content_sha256) row is never rewritten.
Only its effective range may move when a newer statute version arrives.
"""
from dataclasses import dataclass, field
from datetime import date
import hashlib
from pathlib import Path

import psycopg

from .embedding import Embedder
from .legalize import LawVersion, blob_url
from .preview import chunk_pages

ISSUERS = {"mfds": "식품의약품안전처", "kcia": "대한화장품협회", "law": "법제처 국가법령정보센터"}


@dataclass
class Document:
    document_key: str
    version_label: str
    title: str
    issuer: str
    collection: str
    source_type: str
    source_url: str
    content_sha256: str
    storage_key: str
    published_on: date | None = None
    effective_from: date | None = None
    effective_until: date | None = None
    chunks: list[dict] = field(default_factory=list)


def from_law(version: LawVersion) -> Document:
    meta = version.meta
    kind = meta.get("법령구분", "")
    issuer = meta.get("소관부처")
    return Document(
        document_key=f"legalize-kr:{version.path.removeprefix('kr/').removesuffix('.md')}",
        version_label=version.version_label,
        title=version.title,
        issuer=", ".join(issuer) if isinstance(issuer, list) else (issuer or "unknown"),
        collection="rules",
        source_type="law" if kind == "법률" else "regulation",
        source_url=blob_url(version),
        content_sha256=version.sha256,
        storage_key=version.storage_key,
        published_on=_date(meta.get("공포일자")),
        effective_from=version.effective_from,
        effective_until=version.effective_until,
        chunks=[{"section": c.section, "context": c.context, "body": c.body, "page_from": None}
                for c in version.chunks],
    )


def read_pdf_pages(path: Path) -> list[str]:
    import pdfplumber

    with pdfplumber.open(path) as pdf:
        if len(pdf.pages) > 500:
            raise ValueError("PUBLIC_DOCUMENT_PAGE_LIMIT")
        return [page.extract_text() or "" for page in pdf.pages]


def from_pdf(source: dict, path: Path, *, pages: list[str] | None = None,
             page_range: tuple[int, int] | None = None, collection: str | None = None,
             key_suffix: str = "") -> Document:
    acquisition = source["acquisition"]
    raw = path.read_bytes()
    pages = pages if pages is not None else read_pdf_pages(path)
    first, last = page_range or (1, len(pages))
    chunks = []
    for chunk in chunk_pages(pages[first - 1:last]):
        page = chunk["page"] + first - 1
        chunks.append({"section": None, "context": source["title"], "body": chunk["body"], "page_from": page})
    return Document(
        document_key=f"pdf:{source['id']}{key_suffix}",
        version_label=str(source.get("reported_version_date") or "unverified"),
        title=source["title"],
        issuer=ISSUERS.get(source.get("provider", ""), source.get("provider", "unknown")),
        collection=collection or acquisition["collection"],
        source_type="case" if (collection or acquisition["collection"]) == "cases" else acquisition["source_type"],
        source_url=acquisition.get("annex_pdf_url") or source["official_page"],
        content_sha256=hashlib.sha256(raw).hexdigest(),
        storage_key=f"file:{path.name}",
        chunks=chunks,
    )


def _date(value: str | None) -> date | None:
    try:
        return date.fromisoformat(value) if value else None
    except ValueError:
        return None


def write(conn: psycopg.Connection, doc: Document, *, corpus_version: str,
          embedder: Embedder | None = None) -> str:
    """Return inserted, exists or range_updated. Commits on success."""
    with conn.cursor() as cur:
        cur.execute(
            """SELECT document_version_id, effective_from, effective_until FROM knowledge.document_versions
               WHERE document_key = %s AND version_label = %s AND content_sha256 = %s""",
            (doc.document_key, doc.version_label, doc.content_sha256))
        existing = cur.fetchone()
        if existing:
            status = "exists"
            if (existing[1], existing[2]) != (doc.effective_from, doc.effective_until):
                cur.execute("""UPDATE knowledge.document_versions SET effective_from = %s, effective_until = %s
                               WHERE document_version_id = %s""",
                            (doc.effective_from, doc.effective_until, existing[0]))
                status = "range_updated"
            if embedder:
                _embed_missing(cur, existing[0], embedder)
            conn.commit()
            return status
        cur.execute(
            """INSERT INTO knowledge.document_versions
               (document_key, version_label, title, issuer, collection_key, source_type, source_url,
                source_verified, published_on, effective_from, effective_until, content_sha256,
                storage_key, corpus_version)
               VALUES (%s,%s,%s,%s,%s,%s,%s,false,%s,%s,%s,%s,%s,%s) RETURNING document_version_id""",
            (doc.document_key, doc.version_label, doc.title, doc.issuer, doc.collection, doc.source_type,
             doc.source_url, doc.published_on, doc.effective_from, doc.effective_until,
             doc.content_sha256, doc.storage_key, corpus_version))
        version_id = cur.fetchone()[0]
        for index, chunk in enumerate(doc.chunks):
            cur.execute(
                """INSERT INTO knowledge.chunks (document_version_id, chunk_index, section, page_from,
                   page_until, body, context) VALUES (%s,%s,%s,%s,%s,%s,%s)""",
                (version_id, index, chunk["section"], chunk["page_from"], chunk["page_from"],
                 chunk["body"], chunk["context"]))
        if embedder:
            _embed_missing(cur, version_id, embedder)
    conn.commit()
    return "inserted"


def _embed_missing(cur, version_id, embedder: Embedder, batch: int = 16) -> None:
    cur.execute("""SELECT chunk_id, coalesce(section, '') || ' ' || body FROM knowledge.chunks
                   WHERE document_version_id = %s AND embedding IS NULL ORDER BY chunk_index""", (version_id,))
    rows = cur.fetchall()
    for start in range(0, len(rows), batch):
        part = rows[start:start + batch]
        for (chunk_id, _), vector in zip(part, embedder.embed([text for _, text in part])):
            cur.execute("""UPDATE knowledge.chunks SET embedding = %s::vector, embedding_model = %s,
                           embedding_revision = %s WHERE chunk_id = %s""",
                        (str(vector), embedder.model_name, embedder.revision, chunk_id))
