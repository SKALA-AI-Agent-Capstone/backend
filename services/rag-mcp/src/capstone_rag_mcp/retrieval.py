"""Search over knowledge.chunks.

Keyword ranking always runs, so the corpus is searchable before an embedding
model is chosen. When chunks carry embeddings from the configured model, the
two rankings are merged with reciprocal rank fusion.
"""
from collections import Counter
from dataclasses import dataclass
from datetime import date
import math
import re

import psycopg
from psycopg.rows import dict_row

from .embedding import Embedder

MIN_COVERAGE = 0.12
RRF_K = 60

CANDIDATES_SQL = """
WITH docs AS (
    SELECT DISTINCT ON (document_key) *
    FROM knowledge.document_versions
    WHERE collection_key = %(collection)s
      AND (effective_from IS NULL OR effective_from <= %(review_date)s)
      AND (effective_until IS NULL OR effective_until >= %(review_date)s)
    ORDER BY document_key, effective_from DESC NULLS LAST, retrieved_at DESC
)
SELECT c.chunk_id::text, c.section, c.context, c.body, c.page_from,
       d.document_key, d.title, d.version_label, d.source_url, d.storage_key,
       d.effective_from, d.effective_until, d.corpus_version
FROM knowledge.chunks c JOIN docs d USING (document_version_id)
"""

VECTOR_SQL = """
SELECT chunk_id::text, 1 - (embedding <=> %(vector)s::vector) AS cosine
FROM knowledge.chunks
WHERE chunk_id = ANY(%(ids)s::uuid[]) AND embedding_model = %(model)s
ORDER BY embedding <=> %(vector)s::vector
LIMIT %(limit)s
"""


@dataclass
class Ranked:
    row: dict
    similarity: float


def bigrams(text: str) -> list[str]:
    compact = re.sub(r"[^0-9A-Za-z가-힣]", "", text).lower()
    return [compact[i:i + 2] for i in range(len(compact) - 1)]


def keyword_rank(query: str, rows: list[dict], *, k1: float = 1.2, b: float = 0.75) -> list[Ranked]:
    """BM25 over character bigrams for ordering; similarity is the idf-weighted share of query bigrams found."""
    query_terms = Counter(bigrams(query))
    if not query_terms or not rows:
        return []
    docs = [Counter(bigrams(f"{row['section'] or ''} {row['body']}")) for row in rows]
    average = sum(sum(d.values()) for d in docs) / len(docs) or 1
    idf = {t: math.log(1 + (len(docs) - n + 0.5) / (n + 0.5))
           for t in query_terms for n in [sum(1 for d in docs if t in d)]}
    total_weight = sum(idf.values()) or 1
    ranked = []
    for row, terms in zip(rows, docs):
        length = sum(terms.values()) or 1
        score = sum(idf[t] * terms[t] * (k1 + 1) / (terms[t] + k1 * (1 - b + b * length / average))
                    for t in query_terms if t in terms)
        coverage = sum(idf[t] for t in query_terms if t in terms) / total_weight
        if coverage >= MIN_COVERAGE:
            ranked.append((score, Ranked(row, round(coverage, 4))))
    return [item for _, item in sorted(ranked, key=lambda pair: pair[0], reverse=True)]


def fuse(keyword: list[Ranked], vector: list[Ranked]) -> list[Ranked]:
    scores: dict[str, float] = {}
    best: dict[str, Ranked] = {}
    for ranking in (keyword, vector):
        for position, item in enumerate(ranking):
            key = item.row["chunk_id"]
            scores[key] = scores.get(key, 0) + 1 / (RRF_K + position + 1)
            if key not in best or item.similarity > best[key].similarity:
                best[key] = item
    return [best[key] for key in sorted(scores, key=scores.get, reverse=True)]


async def search(dsn: str, *, collection: str, query: str, top_k: int, review_date: date,
                 embedder: Embedder | None = None) -> tuple[str, list[Ranked], list[str]]:
    """Return (mode, ranked, corpus_versions). Raises psycopg.Error on database failure.

    An empty candidate list means the collection has nothing indexed for the date.
    """
    async with await psycopg.AsyncConnection.connect(dsn, connect_timeout=3, row_factory=dict_row) as conn:
        await conn.execute("SET statement_timeout = '5000ms'")
        rows = await (await conn.execute(CANDIDATES_SQL, {"collection": collection, "review_date": review_date})).fetchall()
        if not rows:
            return "keyword", [], []
        corpus = sorted({row["corpus_version"] for row in rows})
        keyword = keyword_rank(query, rows)
        if embedder is None:
            return "keyword", keyword[:top_k], corpus
        vector_rows = await (await conn.execute(VECTOR_SQL, {
            "vector": str(embedder.embed([query])[0]), "ids": [row["chunk_id"] for row in rows],
            "model": embedder.model_name, "limit": max(top_k * 4, 20),
        })).fetchall()
        if not vector_rows:
            return "keyword", keyword[:top_k], corpus
        by_id = {row["chunk_id"]: row for row in rows}
        vector = [Ranked(by_id[v["chunk_id"]], round(max(0.0, min(1.0, v["cosine"])), 4)) for v in vector_rows]
        return "hybrid", fuse(keyword, vector)[:top_k], corpus
