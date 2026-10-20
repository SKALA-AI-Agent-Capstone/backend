import os

from fastmcp import FastMCP
from fastmcp.server.auth.providers.jwt import StaticTokenVerifier
from starlette.responses import JSONResponse

from capstone_shared.contracts import (
    ScaffoldResult,
    SearchCollection,
    SearchRulesInput,
    SearchRulesResult,
    SearchStatus,
)
from capstone_shared.probes import database_probe
from capstone_shared.settings import internal_token, required_env


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
