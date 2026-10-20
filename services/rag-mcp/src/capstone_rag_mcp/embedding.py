"""Optional embedding model. Disabled unless RAG_EMBEDDING_MODEL is set and the ingest extra is installed.

Documents and queries must use the same model and revision, so both are stored
on every chunk and search only compares chunks made by the loaded model.
"""
from dataclasses import dataclass
import os
from typing import Any

DIMENSION = 1024


@dataclass
class Embedder:
    model_name: str
    revision: str
    model: Any

    def embed(self, texts: list[str]) -> list[list[float]]:
        vectors = self.model.encode(texts, normalize_embeddings=True)
        result = [list(map(float, vector)) for vector in vectors]
        if any(len(vector) != DIMENSION for vector in result):
            raise ValueError("EMBEDDING_DIMENSION_MISMATCH")
        return result


def load_embedder() -> Embedder | None:
    name = os.getenv("RAG_EMBEDDING_MODEL", "").strip()
    if not name:
        return None
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError:
        return None
    revision = os.getenv("RAG_EMBEDDING_REVISION", "").strip() or None
    model = SentenceTransformer(name, revision=revision)
    return Embedder(model_name=name, revision=revision or "unpinned", model=model)
