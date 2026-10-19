#!/usr/bin/env python3
"""Exercise the running development stack without modifying corpus data."""
import asyncio
import httpx
import psycopg
from fastmcp import Client

from capstone_shared.settings import internal_token, required_env


async def main() -> None:
    token = internal_token()
    headers = {"Authorization": f"Bearer {token}"}
    async with httpx.AsyncClient(timeout=15) as http:
        response = await http.get(required_env("BACKEND_BASE_URL") + "/api/dev/status")
        response.raise_for_status()
        data = response.json()
        assert data["dependencies"]["application_database"]["status"] == "ok"
        assert data["dependencies"]["agent"]["status"] == "ok"
        dependencies = data["dependencies"]["agent_environment"]["dependencies"]
        assert all(item["status"] == "ok" for item in dependencies.values())
        denied = await http.get(required_env("BACKEND_BASE_URL") + "/internal/bootstrap")
        assert denied.status_code == 401, "Spring internal API must require a token"
        ready = await http.get(required_env("AGENT_BASE_URL") + "/health/ready")
        assert ready.status_code == 200
        not_implemented = await http.post(
            required_env("AGENT_BASE_URL") + "/internal/runs", headers=headers
        )
        assert not_implemented.status_code == 501
        for name in ["BUSINESS_MCP_URL", "RAG_MCP_URL"]:
            denied = await http.post(required_env(name), json={})
            assert denied.status_code == 401, f"{name} must require a token"

    async with Client(required_env("BUSINESS_MCP_URL"), auth=token) as client:
        info = await client.call_tool("get_environment_info", {})
        assert info.structured_content["status"] == "ok"
    async with Client(required_env("RAG_MCP_URL"), auth=token) as client:
        result = await client.call_tool("search_rules", {
            "query": {
                "claim_id": "smoke-c1",
                "claim_text": "합성 테스트 문구",
                "review_date": "2026-10-19",
            }
        })
        assert result.structured_content["status"] == "NOT_IMPLEMENTED"
        assert result.structured_content["evidence"] == []
        assert result.structured_content["collection"] == "rules"
        cases = await client.call_tool("search_cases", {
            "query": {
                "claim_id": "smoke-c2",
                "claim_text": "합성 사례 검색",
                "review_date": "2026-10-19",
            }
        })
        assert cases.structured_content["collection"] == "cases"
        assert cases.structured_content["status"] == "NOT_IMPLEMENTED"
        assert cases.structured_content["evidence"] == []
    async with await psycopg.AsyncConnection.connect(
        required_env("RAG_DATABASE_URL"), connect_timeout=3
    ) as connection:
        async with connection.cursor() as cursor:
            await cursor.execute("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
            assert await cursor.fetchone(), "pgvector extension must be installed"
            await cursor.execute("SELECT count(*) FROM knowledge.document_versions")
            await cursor.fetchone()
            await cursor.execute("SELECT collection_key, source_verified FROM knowledge.document_versions LIMIT 0")
            await cursor.execute(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                "WHERE attrelid = 'knowledge.chunks'::regclass AND attname = 'embedding'"
            )
            assert await cursor.fetchone() == ("vector(1024)",)
            zero_vector = "[" + ",".join(["0"] * 1024) + "]"
            unit_vector = "[1," + ",".join(["0"] * 1023) + "]"
            await cursor.execute("SELECT %s::vector <-> %s::vector", (zero_vector, unit_vector))
            assert await cursor.fetchone() == (1.0,)
            await cursor.execute(
                "SELECT has_table_privilege(current_user, 'knowledge.chunks', 'INSERT'), "
                "has_table_privilege(current_user, 'knowledge.chunks', 'SELECT'), "
                "has_schema_privilege(current_user, 'app', 'USAGE')"
            )
            insert, select, app_access = await cursor.fetchone()
            assert (insert, select, app_access) == (False, True, False)
    print("PASS: HTTP, MCP authentication, cross-service calls, pgvector and read-only DB access.")
    print("No document was inserted and no advertisement was evaluated.")


if __name__ == "__main__":
    asyncio.run(main())
