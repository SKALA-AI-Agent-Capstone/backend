import os

import httpx
from fastmcp import FastMCP
from fastmcp.server.auth.providers.jwt import StaticTokenVerifier
from starlette.responses import JSONResponse

from capstone_shared.contracts import ScaffoldResult
from capstone_shared.settings import internal_token, required_env


def create_server() -> FastMCP:
    verifier = StaticTokenVerifier(
        tokens={internal_token(): {"client_id": "capstone-agent", "scopes": ["tools:read"]}},
        required_scopes=["tools:read"],
    )
    mcp = FastMCP("business-mcp", auth=verifier)

    @mcp.custom_route("/health/live", methods=["GET"])
    async def live(request) -> JSONResponse:
        return JSONResponse({"service": "business-mcp", "status": "ok"})

    @mcp.tool(annotations={"readOnlyHint": True})
    async def get_environment_info() -> dict:
        """Check the authenticated Spring Boot internal API. Contains no product data."""
        async with httpx.AsyncClient(timeout=4) as client:
            response = await client.get(
                required_env("BACKEND_BASE_URL") + "/internal/bootstrap",
                headers={"Authorization": f"Bearer {internal_token()}"},
            )
            response.raise_for_status()
            return response.json()

    @mcp.tool(annotations={"readOnlyHint": True})
    async def get_product_context(product_id: str) -> ScaffoldResult:
        """Contract placeholder only. Product lookup and tenant authorization are not implemented."""
        return ScaffoldResult(message="Product lookup is not implemented. No product was queried.")

    return mcp


def main() -> None:
    create_server().run(
        transport="http",
        host=os.getenv("SERVICE_HOST", "127.0.0.1"),
        port=int(os.getenv("SERVICE_PORT", "8002")),
    )
