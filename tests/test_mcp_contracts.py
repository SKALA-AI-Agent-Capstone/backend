from fastmcp import Client

from capstone_business_mcp.server import create_server as create_business
from capstone_rag_mcp.server import create_server as create_rag


async def test_rag_mcp_call_does_not_claim_an_empty_corpus_is_compliant():
    async with Client(create_rag()) as client:
        result = await client.call_tool("search_rules", {
            "query": {
                "claim_id": "c1",
                "claim_text": "수분감 30% 개선",
                "review_date": "2026-10-19",
            },
        })
        content = result.structured_content
        assert content["claim_id"] == "c1"
        assert content["status"] == "NOT_IMPLEMENTED"
        assert content["evidence"] == []
        assert content["corpus_version"] is None


async def test_case_retrieval_is_separate_from_primary_rules():
    async with Client(create_rag()) as client:
        result = await client.call_tool("search_cases", {
            "query": {"claim_id": "c2", "claim_text": "합성 광고", "review_date": "2026-10-19"}
        })
        assert result.structured_content["collection"] == "cases"
        assert result.structured_content["status"] == "NOT_IMPLEMENTED"
        assert result.structured_content["evidence"] == []
        assert result.structured_content["warnings"]


async def test_business_mcp_has_no_approval_or_mutation_tool_in_bootstrap():
    async with Client(create_business()) as client:
        tools = await client.list_tools()
        assert {tool.name for tool in tools} == {
            "get_environment_info", "get_product_context"
        }
        result = await client.call_tool("get_product_context", {"product_id": "sample"})
        assert result.structured_content["status"] == "NOT_IMPLEMENTED"
