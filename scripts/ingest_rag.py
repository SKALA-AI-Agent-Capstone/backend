#!/usr/bin/env python3
"""Load the public RAG corpus. Run as a batch, never per review request.

  legalize  화장품법 법률, 시행령, 시행규칙 from legalize-kr (S05, S06 본문)
  pdf       Local PDFs for the remaining sources (S01~S04, S06 별표 5, S07~S09)

Sources and routes come from the acquisition field of reference-sources.json.
"""
import argparse
import asyncio
from datetime import date
import json
import os
from pathlib import Path

import psycopg
from capstone_rag_mcp.embedding import load_embedder
from capstone_rag_mcp.ingest import from_law, from_pdf, read_pdf_pages, write
from capstone_rag_mcp.legalize import fetch_versions
from local_config import ROOT, load_env

REGISTRY = ROOT / "services/api/src/main/resources/reference-sources.json"
PDF_DIRS = [ROOT / "data/sources", ROOT / "data/source-audit"]


def sources() -> list[dict]:
    return json.loads(REGISTRY.read_text(encoding="utf-8"))


def legalize_paths() -> list[str]:
    paths = []
    for source in sources():
        acquisition = source["acquisition"]
        if acquisition["method"].startswith("legalize_kr"):
            paths += [p for p in acquisition["paths"] if p not in paths]
    return paths


def find_pdf(name: str) -> Path | None:
    return next((folder / name for folder in PDF_DIRS if (folder / name).is_file()), None)


def pdf_documents() -> tuple[list, list[dict]]:
    documents, skipped = [], []
    for source in sources():
        acquisition = source["acquisition"]
        if "pdf" not in acquisition["method"].split("+"):
            continue
        path = find_pdf(acquisition["file"])
        if path is None:
            skipped.append({"id": source["id"], "reason": "PDF_NOT_FOUND", "expected": f"data/sources/{acquisition['file']}"})
            continue
        if acquisition["collection"] == "mixed_rules_cases":
            ranges = acquisition.get("page_ranges")
            if not ranges:
                skipped.append({"id": source["id"], "reason": "PAGE_RANGES_REQUIRED_FOR_MIXED_DOCUMENT"})
                continue
            pages = read_pdf_pages(path)
            for collection, (first, last) in ranges.items():
                documents.append(from_pdf(source, path, pages=pages, page_range=(first, last),
                                          collection=collection, key_suffix=f":{collection}"))
            continue
        documents.append(from_pdf(source, path))
    return documents, skipped


async def collect(args) -> tuple[list, list[dict]]:
    if args.command == "legalize":
        documents = []
        for path in legalize_paths():
            for version in await fetch_versions(path, as_of=args.as_of):
                documents.append(from_law(version))
        return documents, []
    return pdf_documents()


def main(args) -> None:
    load_env(args.env_file)
    documents, skipped = asyncio.run(collect(args))
    for doc in documents:
        print(json.dumps({"document": doc.document_key, "version": doc.version_label, "collection": doc.collection,
                          "effective": [str(doc.effective_from), str(doc.effective_until)], "chunks": len(doc.chunks)},
                         ensure_ascii=False))
    for item in skipped:
        print(json.dumps({"skipped": item}, ensure_ascii=False))
    if args.dry_run:
        print("Dry run: nothing written.")
        return
    embedder = load_embedder() if args.embed else None
    if args.embed and embedder is None:
        raise SystemExit("Set RAG_EMBEDDING_MODEL and install the ingest extra to embed.")
    with psycopg.connect(os.environ["RAG_INGEST_DATABASE_URL"], connect_timeout=5) as conn:
        for doc in documents:
            status = write(conn, doc, corpus_version=args.corpus_version, embedder=embedder)
            print(json.dumps({"document": doc.document_key, "version": doc.version_label, "status": status},
                             ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["legalize", "pdf"])
    parser.add_argument("--env-file")
    parser.add_argument("--as-of", type=date.fromisoformat, default=date.today(),
                        help="Keep statute versions back to the one in force on this date.")
    parser.add_argument("--corpus-version", default=f"{date.today().isoformat()}.1")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--embed", action="store_true")
    main(parser.parse_args())
