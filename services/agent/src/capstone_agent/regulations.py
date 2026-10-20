"""Legal basis lookup through rag-mcp search_regulations.

The agent picks the query: the claim phrase plus statute wording for its claim
type. A raw ad phrase rarely shares words with statute text, so the hint is what
lets keyword retrieval reach 제13조 or 제14조. Hits are attached as citation
candidates for the reviewer; the agent does not decide legality here.
"""
import asyncio
from datetime import date
import re

from fastmcp import Client
from fastmcp.client.transports import StreamableHttpTransport

from capstone_shared.settings import internal_token, required_env

# Wording taken from 화장품법 제13조, 제14조 and 시행규칙 제2조.
CLAIM_HINTS = {
    "drug": "의약품으로 잘못 인식할 우려가 있는 표시 또는 광고",
    "wrinkle": "기능성화장품 주름개선 심사결과와 다른 표시 또는 광고",
    "whitening": "기능성화장품 미백 심사결과와 다른 표시 또는 광고",
    "clinical": "표시ㆍ광고 내용의 실증 시험 자료",
    "absolute": "사실과 다르게 소비자를 속이거나 잘못 인식하도록 할 우려가 있는 표시 또는 광고",
    "exaggeration": "사실과 다르게 소비자를 속이거나 잘못 인식하도록 할 우려가 있는 표시 또는 광고",
    "ingredient": "화장품 원료 성분 표시 기재",
    "patent": "표시ㆍ광고 내용의 실증 특허",
}
TOP_SIMILARITY = 0.25    # keep the best hit only if it shares this much statute wording
EXTRA_SIMILARITY = 0.45  # further hits need a stronger match
MAX_BASIS = 3
PER_QUERY = 2
MAX_FINDINGS = 40
SOURCE_TYPES = {"law": "law", "regulation": "law", "guideline": "guideline", "association": "association"}


def build_queries(finding: dict) -> list[str]:
    """One search per legal angle. Mixing two hints in one query dilutes both, so they run separately."""
    claim_type = finding.get("claimType", "")
    queries = [f"{finding['phrase']}\n{CLAIM_HINTS.get(claim_type, CLAIM_HINTS['exaggeration'])}"]
    # A figure (90%, 7일) is a factual claim that must be substantiated, whatever its claim type.
    if claim_type != "clinical" and re.search(r"\d", finding["phrase"]):
        queries.append(f"{finding['phrase']}\n{CLAIM_HINTS['clinical']}")
    return queries


def to_basis(hit: dict) -> dict:
    """Map a RegulationHit to the frontend basis shape (BasisItem.vue)."""
    article = hit.get("section") or (f"p.{hit['page']}" if hit.get("page") else "")
    return {
        "sourceId": hit["document_key"],
        "sourceType": SOURCE_TYPES.get(hit.get("source_type", ""), "law"),
        "sourceName": hit["source"],
        "article": article,
        "plain": "광고 주장과 관련된 조문 후보입니다. 적용 여부와 위반 판단은 담당자가 원문으로 확인합니다.",
        "original": hit["excerpt"],
        "version": hit["version"],
        "revisedAt": hit.get("effective_from"),
        "scope": hit.get("scope"),
        "sourceUrl": hit.get("source_url"),
        "similarity": hit.get("similarity"),
        "retrieval": "rag-mcp search_regulations",
    }


def select_hits(hits: list[dict]) -> list[dict]:
    selected = []
    for index, hit in enumerate(hits):
        limit = TOP_SIMILARITY if index == 0 else EXTRA_SIMILARITY
        if hit.get("similarity", 0) >= limit:
            selected.append(hit)
        if len(selected) == PER_QUERY:
            break
    return selected


def merge_hits(responses: list[dict]) -> list[dict]:
    merged, seen = [], set()
    for response in responses:
        for hit in select_hits(response.get("hits", [])):
            key = (hit["document_key"], hit.get("section"), hit.get("page"))
            if key not in seen:
                seen.add(key)
                merged.append(hit)
    return merged[:MAX_BASIS]


async def search_basis(findings: list[dict], *, review_date: date | None = None, call=None) -> dict:
    """Return {"findings": [...with basis], "status": ..., "snapshot": [...], "warnings": [...]}.

    status: connected (searched), not_indexed (rag-mcp has no corpus), error (rag-mcp unreachable),
    skipped (no claims to search).
    """
    if not findings:
        return {"findings": findings, "status": "skipped", "snapshot": [], "warnings": []}
    day = (review_date or date.today()).isoformat()
    targets = findings[:MAX_FINDINGS]
    jobs = [(index, query) for index, finding in enumerate(targets) for query in build_queries(finding)]
    results: list[dict | None] = [None] * len(jobs)

    async def run(client_call):
        limit = asyncio.Semaphore(4)

        async def one(position, query):
            async with limit:
                try:
                    results[position] = await client_call("search_regulations",
                        {"query_text": query, "top_k": 3, "review_date": day})
                except Exception:
                    results[position] = None
        await asyncio.gather(*(one(position, query) for position, (_, query) in enumerate(jobs)))

    try:
        if call is not None:
            await run(call)
        else:
            transport = StreamableHttpTransport(required_env("RAG_MCP_URL"), auth=internal_token())
            async with asyncio.timeout(60):
                async with Client(transport) as client:
                    async def client_call(name, arguments):
                        response = await client.call_tool(name, arguments)
                        if response.is_error:
                            raise RuntimeError("RAG_TOOL_FAILED")
                        return response.structured_content
                    await run(client_call)
    except Exception:
        return {"findings": findings, "status": "error", "snapshot": [],
                "warnings": ["LEGAL_SEARCH_UNAVAILABLE"]}

    statuses = [r.get("status") if r else "SEARCH_ERROR" for r in results]
    if statuses and all(s == "CORPUS_NOT_INDEXED" for s in statuses):
        return {"findings": findings, "status": "not_indexed", "snapshot": [],
                "warnings": ["LEGAL_CORPUS_NOT_INDEXED"]}
    if all(s == "SEARCH_ERROR" for s in statuses):
        return {"findings": findings, "status": "error", "snapshot": [], "warnings": ["LEGAL_SEARCH_FAILED"]}

    searched: dict[int, list[dict]] = {}
    for (index, _), result in zip(jobs, results):
        if result is not None and result.get("status") not in ("SEARCH_ERROR", "CORPUS_NOT_INDEXED"):
            searched.setdefault(index, []).append(result)
    enriched, snapshot, warnings = [], {}, []
    for index, finding in enumerate(findings):
        if index not in searched:
            enriched.append({**finding, "legalSearch": "failed" if index < len(targets) else "not_searched"})
            continue
        hits = merge_hits(searched[index])
        basis = [to_basis(hit) for hit in hits]
        for hit in hits:
            snapshot[(hit["source"], hit["version"])] = {"name": hit["source"], "version": hit["version"],
                                                          "revisedAt": hit.get("effective_from")}
        enriched.append({**finding, "basis": basis, "baseBasis": basis,
                         "legalSearch": "basis_found" if basis else "no_match",
                         "title": "법령 근거 후보와 대조할 문구" if basis else finding.get("title")})
    if any(s == "SEARCH_ERROR" for s in statuses):
        warnings.append("LEGAL_SEARCH_PARTIAL")
    if len(findings) > MAX_FINDINGS:
        warnings.append(f"LEGAL_SEARCH_LIMITED_TO_{MAX_FINDINGS}")
    return {"findings": enriched, "status": "connected", "snapshot": list(snapshot.values()), "warnings": warnings}
