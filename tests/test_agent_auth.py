import httpx

from capstone_agent.app import create_app


async def test_internal_execution_rejects_missing_token():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.post("/internal/runs")
        assert response.status_code == 401
        assert "NOT_IMPLEMENTED" not in response.text


async def test_authenticated_execution_is_explicitly_unimplemented():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.post(
            "/internal/runs",
            headers={"Authorization": "Bearer unit-test-only-token-not-for-production-12345"},
        )
        assert response.status_code == 501
        assert response.json()["code"] == "NOT_IMPLEMENTED"


async def test_database_failure_is_not_reported_as_ready():
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
    ) as client:
        response = await client.get("/health/ready")
        assert response.status_code == 503
        assert response.json()["status"] == "down"
        assert "postgresql://" not in response.text
