import asyncio
import os
import httpx

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
from .review_models import RunRequest, ResumeRequest
from .review_engine import Provider, prepare_assets, build_graph, progress
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.types import Command


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
    app = FastAPI(title="Cosmetics review Agent local MVP", version="0.2.0-mvp")

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
            "implemented": ["health", "authenticated-mcp-discovery", "ad-parsing", "independent-text-and-photo-paths", "langgraph", "postgres-hitl"],
            "planned": ["rag-retrieval", "evidence-claim-matching", "queue-worker", "billing"],
        }

    @app.post("/internal/runs", dependencies=[Depends(require_internal)])
    async def create_run(request: RunRequest) -> dict:
        try:
            async with asyncio.timeout(270):
                native, tiles = await prepare_assets(request)
                async with AsyncPostgresSaver.from_conn_string(required_env("AGENT_DATABASE_URL")) as saver:
                    await saver.setup()
                    graph = build_graph(native, tiles, Provider(), saver, request)
                    config = {"configurable":{"thread_id":f"{request.tenantId}:{request.reviewId}:v{request.versionNo}"}}
                    output = await graph.ainvoke({"reviewId":request.reviewId,"tenantId":request.tenantId,
                        "versionNo":request.versionNo,"product":request.product}, config)
                    await progress(request,4)
                    return {"result":output["result"],"hitlStatus":"pending","contract_version":"0.2.0-mvp"}
        except (ValueError, httpx.HTTPStatusError) as error:
            raise HTTPException(status_code=422,detail="INPUT_UNREADABLE_OR_EXPIRED") from None

    @app.post("/internal/runs/resume", dependencies=[Depends(require_internal)])
    async def resume(request: ResumeRequest) -> dict:
        async with AsyncPostgresSaver.from_conn_string(required_env("AGENT_DATABASE_URL")) as saver:
            graph=build_graph([],[],Provider(),saver)
            config={"configurable":{"thread_id":f"{request.tenantId}:{request.reviewId}:v{request.versionNo}"}}
            snapshot=await graph.aget_state(config)
            if not snapshot.values:
                raise HTTPException(status_code=404,detail="CHECKPOINT_NOT_FOUND")
            if not snapshot.next:
                return {"reportReady":True,"alreadyResumed":True}
            output=await graph.ainvoke(Command(resume=request.state),config)
            return {"reportReady":output.get("reportReady",False)}

    @app.post("/internal/runs/delete", dependencies=[Depends(require_internal)])
    async def delete_runs(request: ResumeRequest) -> dict:
        async with AsyncPostgresSaver.from_conn_string(required_env("AGENT_DATABASE_URL")) as saver:
            await saver.setup()
            for no in range(1,request.versionNo+1):
                await saver.adelete_thread(f"{request.tenantId}:{request.reviewId}:v{no}")
        return {"deleted":True}

    return app


def main() -> None:
    import uvicorn

    uvicorn.run(
        create_app(),
        host=os.getenv("SERVICE_HOST", "127.0.0.1"),
        port=int(os.getenv("SERVICE_PORT", "8001")),
    )
