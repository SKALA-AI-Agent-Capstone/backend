from datetime import date
import os
from typing import Annotated

from fastmcp import FastMCP
from fastmcp.server.auth.providers.jwt import StaticTokenVerifier
import psycopg
from pydantic import Field
from starlette.responses import JSONResponse

from capstone_shared.contracts import (
    ScaffoldResult,
    SearchCollection,
    SearchRulesInput,
    SearchRulesResult,
    SearchStatus,
)
from capstone_shared.probes import database_probe
from capstone_shared.rag_contracts import (
    PrecedentHit,
    PrecedentSearchResult,
    RegulationHit,
    RegulationSearchResult,
    RetrievalStatus,
)
from capstone_shared.settings import internal_token, required_env
from .embedding import load_embedder
from .retrieval import Ranked, search


def regulation_hit(item: Ranked) -> RegulationHit:
    row = item.row
    acquisition = "legalize-kr" if (row["storage_key"] or "").startswith("legalize-kr@") else "pdf"
    return RegulationHit(
        source=row["title"], version=row["version_label"], scope=row["context"] or row["title"],
        excerpt=row["body"], similarity=item.similarity, document_key=row["document_key"],
        section=row["section"], page=row["page_from"], source_url=row["source_url"],
        acquisition=acquisition, effective_from=row["effective_from"], effective_until=row["effective_until"],
    )


def create_server() -> FastMCP:
    verifier = StaticTokenVerifier(
        tokens={internal_token(): {"client_id": "capstone-agent", "scopes": ["tools:read"]}},
        required_scopes=["tools:read"],
    )
    mcp = FastMCP("rag-mcp", auth=verifier)

    @mcp.custom_route("/health/live", methods=["GET"])
    async def live(request) -> JSONResponse:
        return JSONResponse({"service": "rag-mcp", "status": "ok"})

    @mcp.custom_route("/health/ready", methods=["GET"])
    async def ready(request) -> JSONResponse:
        db = await database_probe(required_env("RAG_DATABASE_URL"), require_vector=True)
        return JSONResponse(
            {"service": "rag-mcp", "status": db["status"], "database": db},
            status_code=200 if db["status"] == "ok" else 503,
        )

    embedder = load_embedder()

    @mcp.tool(annotations={"readOnlyHint": True})
    async def search_regulations(
        query_text: Annotated[str, Field(min_length=1, max_length=3000)],
        top_k: Annotated[int, Field(ge=1, le=10)] = 5,
        review_date: date | None = None,
    ) -> RegulationSearchResult:
        """Search statutes and official standards (collection A: 화장품법, 시행규칙, 식약처 지침, 고시, 협회 기준).

        Pass one advertising claim with its context as query_text. Returns verbatim excerpts with
        source, version and effective dates, filtered to the versions in force on review_date
        (default today). similarity is a retrieval score, not a violation probability.
        Check status: CORPUS_NOT_INDEXED and SEARCH_ERROR mean nothing was checked.
        """
        day = review_date or date.today()
        try:
            mode, ranked, corpus = await search(required_env("RAG_DATABASE_URL"), collection="rules",
                                                query=query_text, top_k=top_k, review_date=day, embedder=embedder)
        except psycopg.Error:
            return RegulationSearchResult(status=RetrievalStatus.SEARCH_ERROR, review_date=day,
                                          warnings=["Knowledge database unavailable. No regulation was checked."])
        if not corpus:
            return RegulationSearchResult(status=RetrievalStatus.CORPUS_NOT_INDEXED, review_date=day,
                                          warnings=["No regulation is indexed for this date. Run scripts/ingest_rag.py."])
        hits = [regulation_hit(item) for item in ranked]
        return RegulationSearchResult(
            status=RetrievalStatus.FOUND if hits else RetrievalStatus.NO_RELEVANT_EVIDENCE,
            review_date=day, retrieval_mode=mode, corpus_versions=corpus, hits=hits,
            warnings=[] if hits else ["No matching provision. This is not a compliance finding."],
        )

    @mcp.tool(annotations={"readOnlyHint": True})
    async def search_precedents(
        query_text: Annotated[str, Field(min_length=1, max_length=3000)],
        top_k: Annotated[int, Field(ge=1, le=10)] = 3,
    ) -> PrecedentSearchResult:
        """Search past violation cases (collection B). Supplementary only, never a legal basis.

        Every hit is trust_level=low and verified=False and must be shown as an unverified reference.
        """
        try:
            mode, ranked, corpus = await search(required_env("RAG_DATABASE_URL"), collection="cases",
                                                query=query_text, top_k=top_k, review_date=date.today(),
                                                embedder=embedder)
        except psycopg.Error:
            return PrecedentSearchResult(status=RetrievalStatus.SEARCH_ERROR,
                                         warnings=["Knowledge database unavailable. No case was checked."])
        if not corpus:
            return PrecedentSearchResult(status=RetrievalStatus.CORPUS_NOT_INDEXED,
                                         warnings=["No case collection is indexed yet."])
        hits = [PrecedentHit(source=item.row["title"], excerpt=item.row["body"], similarity=item.similarity,
                             document_key=item.row["document_key"], page=item.row["page_from"],
                             source_url=item.row["source_url"]) for item in ranked]
        return PrecedentSearchResult(
            status=RetrievalStatus.FOUND if hits else RetrievalStatus.NO_RELEVANT_EVIDENCE,
            retrieval_mode=mode, corpus_versions=corpus, hits=hits,
        )

    @mcp.tool(annotations={"readOnlyHint": True})
    async def search_rules(query: SearchRulesInput) -> SearchRulesResult:
        """Placeholder for public rules retrieval. No corpus is indexed or searched yet."""
        return SearchRulesResult(
            claim_id=query.claim_id,
            status=SearchStatus.NOT_IMPLEMENTED,
            warnings=["Retrieval is not implemented. Empty evidence does not imply compliance."],
        )

    @mcp.tool(annotations={"readOnlyHint": True})
    async def search_cases(query: SearchRulesInput) -> SearchRulesResult:
        """Placeholder for supplementary case retrieval, isolated from primary rules."""
        return SearchRulesResult(
            claim_id=query.claim_id,
            collection=SearchCollection.CASES,
            status=SearchStatus.NOT_IMPLEMENTED,
            warnings=["Case retrieval is not implemented. Cases are supplementary and require source verification."],
        )

    @mcp.tool(annotations={"readOnlyHint": True})
    async def get_evidence(evidence_id: str) -> ScaffoldResult:
        """Placeholder for immutable source retrieval. Does not invent a citation."""
        return ScaffoldResult(message="Source lookup is not implemented. No citation was retrieved.")

    return mcp


def main() -> None:
    create_server().run(
        transport="http",
        host=os.getenv("SERVICE_HOST", "127.0.0.1"),
        port=int(os.getenv("SERVICE_PORT", "8003")),
    )
