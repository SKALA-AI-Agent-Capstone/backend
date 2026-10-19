import pytest
from pydantic import ValidationError

from capstone_shared.contracts import SearchRulesInput


@pytest.mark.parametrize("override", [
    {"top_k": 100},
    {"claim_text": ""},
    {"review_date": "not-a-date"},
    {"tenant_id": "model-must-not-select-a-tenant"},
])
def test_retrieval_rejects_unbounded_or_unauthorized_fields(override):
    value = {
        "claim_id": "c1",
        "claim_text": "수분감 30% 개선",
        "review_date": "2026-10-19",
        **override,
    }
    with pytest.raises(ValidationError):
        SearchRulesInput.model_validate(value)
