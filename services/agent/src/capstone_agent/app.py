import asyncio
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from fastmcp import Client

from capstone_shared import CONTRACT_VERSION
from capstone_shared.probes import database_probe
from capstone_shared.settings import (
    internal_token,
    required_env,
    valid_internal_authorization,
)


async def require_internal(authorization: str | None = Header(default=None)) -> None:
    if not valid_internal_authorization(authorization):
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")


async def mcp_probe(url: str) -> dict:
    try:
        async with asyncio.timeout(5):
            async with Client(url, auth=internal_token()) as client:
                tools = await client.list_tools()
        return {"status": "ok", "tools": [tool.name for tool in tools]}
    except Exception:
        return {"status": "down", "reason": "MCP_UNAVAILABLE"}


def create_app() -> FastAPI:
    internal_token()
    app = FastAPI(title="Capstone Agent development scaffold", version=CONTRACT_VERSION)

    @app.get("/health/live")
    async def live() -> dict:
        return {"service": "agent", "status": "ok", "mode": "development-scaffold"}

    @app.get("/health/ready")
    async def ready() -> JSONResponse:
        db = await database_probe(required_env("AGENT_DATABASE_URL"))
        return JSONResponse(
            {"service": "agent", "status": db["status"], "database": db},
            status_code=200 if db["status"] == "ok" else 503,
        )

    @app.get("/internal/environment", dependencies=[Depends(require_internal)])
    async def environment() -> dict:
        database, business, rag = await asyncio.gather(
            database_probe(required_env("AGENT_DATABASE_URL")),
            mcp_probe(required_env("BUSINESS_MCP_URL")),
            mcp_probe(required_env("RAG_MCP_URL")),
        )
        return {
            "contract_version": CONTRACT_VERSION,
            "mode": "development-scaffold",
            "dependencies": {
                "agent_database": database,
                "business_mcp": business,
                "rag_mcp": rag,
            },
            "implemented": ["health", "authenticated-mcp-discovery"],
            "planned": ["claim-extraction", "langgraph", "hitl", "queue-worker"],
        }

    @app.post("/internal/runs", dependencies=[Depends(require_internal)])
    async def create_run() -> JSONResponse:
        return JSONResponse(
            {
                "contract_version": CONTRACT_VERSION,
                "code": "NOT_IMPLEMENTED",
                "message": "Agent execution is assigned to the Agent developer.",
            },
            status_code=501,
        )

    return app


def main() -> None:
    import uvicorn

    uvicorn.run(
        create_app(),
        host=os.getenv("SERVICE_HOST", "127.0.0.1"),
        port=int(os.getenv("SERVICE_PORT", "8001")),
    )
