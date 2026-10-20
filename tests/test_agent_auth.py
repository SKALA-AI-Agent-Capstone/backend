import httpx

from capstone_agent.app import create_app


async def test_internal_execution_rejects_missing_token():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.post("/internal/runs")
        assert response.status_code == 401
        assert "NOT_IMPLEMENTED" not in response.text


async def test_authenticated_execution_requires_scoped_input():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.post(
            "/internal/runs",
            headers={"Authorization": "Bearer unit-test-only-token-not-for-production-12345"},
        )
        assert response.status_code == 422


async def test_database_failure_is_not_reported_as_ready():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.get("/health/ready")
        assert response.status_code == 503
        assert response.json()["status"] == "down"
        assert "postgresql://" not in response.text


async def test_rewrite_without_model_key_is_reported_not_invented(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.post(
            "/internal/rewrites",
            headers={"Authorization": "Bearer unit-test-only-token-not-for-production-12345"},
            json={"reviewId": "r", "tenantId": "t", "phrase": "7일 주름 개선"},
        )
        assert response.status_code == 503
        assert response.json()["detail"] == "TEXT_MODEL_NOT_CONFIGURED"


async def test_rewrite_rejects_missing_token():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.post("/internal/rewrites", json={"reviewId": "r", "tenantId": "t", "phrase": "x"})
        assert response.status_code == 401
