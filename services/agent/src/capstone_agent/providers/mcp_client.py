"""Thin MCP client wrapper for the agent's tool-selection loop.

app.py의 mcp_probe와 같은 인증 방식(fastmcp Client + internal_token)을 재사용한다.
business-mcp/rag-mcp 서버 코드는 건드리지 않고, agent 쪽 호출자만 구현한다.
"""

from __future__ import annotations

from typing import Any

from fastmcp import Client

from capstone_shared.settings import internal_token, required_env


async def call_rag_tool(tool_name: str, arguments: dict[str, Any]) -> Any:
    url = required_env("RAG_MCP_URL")
    async with Client(url, auth=internal_token()) as client:
        return await client.call_tool(tool_name, arguments)


async def call_business_tool(tool_name: str, arguments: dict[str, Any]) -> Any:
    url = required_env("BUSINESS_MCP_URL")
    async with Client(url, auth=internal_token()) as client:
        return await client.call_tool(tool_name, arguments)
