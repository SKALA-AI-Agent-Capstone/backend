from types import SimpleNamespace

import capstone_agent.review_engine as engine
from capstone_agent.review_engine import Provider, select_business_tools


async def test_tool_selection_call_uses_max_completion_tokens(monkeypatch):
    # gpt-5 family models reject max_tokens on Chat Completions with a 400.
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test-not-real")
    provider = Provider()
    sent = {}
    async def create(**kwargs):
        sent.update(kwargs)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="", tool_calls=[]))])
    provider.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    await provider.call_with_tools([{"role": "user", "content": "합성"}], engine.BUSINESS_TOOL_SPECS)
    assert "max_tokens" not in sent and sent["max_completion_tokens"] > 0


async def test_failed_tool_selection_still_runs_relevant_tools(monkeypatch):
    calls = []
    async def tools(name, arguments, request=None):
        calls.append(name)
        return {"status": "found"} if name == "verify_functional_approval" else {"status": "extracted", "text": ""}
    monkeypatch.setattr(engine, "mcp_call", tools)
    class DownProvider:
        client = True
        async def call_with_tools(self, messages, specs):
            raise RuntimeError("provider unavailable")
    request = SimpleNamespace(assets=[SimpleNamespace(role="evidence", model_dump=lambda: {"id": "e1", "role": "evidence"})])
    state = {"product": {"name": "합성 크림"}, "texts": [{"text": "주름 개선"}], "findings": [],
             "tenantId": "t", "reviewId": "r"}
    evidence, public, patents, warnings, trace = await select_business_tools(DownProvider(), state, request)
    assert "TOOL_SELECTION_FAILED" in warnings
    assert sorted(calls) == ["structure_evidence", "verify_functional_approval"]
    assert public == {"status": "found"}
    assert {"tool": "verify_functional_approval", "called": True, "fallback": True} in trace
    assert not any(t["tool"] == "verify_patent" and t.get("called") for t in trace)  # not relevant, not run
