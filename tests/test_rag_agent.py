from langgraph.checkpoint.memory import InMemorySaver

from capstone_agent.regulations import build_queries, merge_hits, search_basis, select_hits


def hit(section, similarity, **extra):
    return {"source": "화장품법", "version": "2026-04-07 제21525호", "scope": "화장품법 > 제3장", "excerpt": f"{section} 원문",
            "similarity": similarity, "trust_level": "high", "source_type": "law", "document_key": "legalize-kr:화장품법/법률",
            "section": section, "page": None, "source_url": "https://github.com/legalize-kr/legalize-kr", "acquisition": "legalize-kr",
            "effective_from": "2026-10-08", "effective_until": "2026-11-26", **extra}


def finding(phrase, claim_type="drug"):
    return {"id": phrase, "phrase": phrase, "claimType": claim_type, "reason": "합성", "basis": [], "baseBasis": [],
            "title": "근거 대조 전 확인할 문구"}


def test_query_adds_statute_wording_for_the_claim_type():
    [query] = build_queries(finding("아토피 치료 크림", "drug"))
    assert query.startswith("아토피 치료 크림") and "의약품으로 잘못 인식할 우려" in query
    assert len(build_queries(finding("주름 90% 감소", "clinical"))) == 1
    primary, substantiation = build_queries(finding("피부 주름 90% 감소", "wrinkle"))
    assert "주름개선" in primary and "실증" in substantiation
    assert len(build_queries(finding("탄력 광채 안티에이징", "wrinkle"))) == 1


def test_hits_from_two_queries_are_merged_without_duplicates():
    merged = merge_hits([{"hits": [hit("제13조", 0.4)]}, {"hits": [hit("제14조", 0.3), hit("제13조", 0.5)]}])
    assert [h["section"] for h in merged] == ["제13조", "제14조"]


def test_weak_hits_are_not_attached_as_basis():
    assert select_hits([hit("제2조 (정의)", 0.16)]) == []
    kept = select_hits([hit("제13조", 0.6), hit("제23조", 0.5), hit("제16조", 0.4)])
    assert [h["section"] for h in kept] == ["제13조", "제23조"]


async def test_hits_become_frontend_basis_and_snapshot():
    calls = []

    async def fake(name, arguments):
        calls.append((name, arguments))
        return {"status": "FOUND", "hits": [hit("제13조 (부당한 표시ㆍ광고 행위 등의 금지)", 0.56)]}

    lookup = await search_basis([finding("아토피 치료 크림")], call=fake)
    assert calls[0][0] == "search_regulations" and "review_date" in calls[0][1]
    assert lookup["status"] == "connected"
    basis = lookup["findings"][0]["basis"][0]
    assert basis["sourceName"] == "화장품법" and basis["article"].startswith("제13조")
    assert basis["original"] == "제13조 (부당한 표시ㆍ광고 행위 등의 금지) 원문"
    assert basis["version"] == "2026-04-07 제21525호" and basis["revisedAt"] == "2026-10-08"
    assert basis["sourceType"] == "law"
    assert lookup["findings"][0]["legalSearch"] == "basis_found"
    assert lookup["snapshot"] == [{"name": "화장품법", "version": "2026-04-07 제21525호", "revisedAt": "2026-10-08"}]


async def test_no_match_is_not_reported_as_compliant():
    async def fake(name, arguments):
        return {"status": "NO_RELEVANT_EVIDENCE", "hits": []}
    lookup = await search_basis([finding("촉촉한 사용감", "exaggeration")], call=fake)
    assert lookup["status"] == "connected"
    assert lookup["findings"][0]["basis"] == [] and lookup["findings"][0]["legalSearch"] == "no_match"


async def test_empty_corpus_and_outage_are_distinct():
    async def empty(name, arguments):
        return {"status": "CORPUS_NOT_INDEXED", "hits": []}

    async def down(name, arguments):
        raise ConnectionError("rag-mcp down")
    assert (await search_basis([finding("a")], call=empty))["status"] == "not_indexed"
    failed = await search_basis([finding("a")], call=down)
    assert failed["status"] == "error" and failed["findings"][0]["basis"] == []
    assert (await search_basis([], call=empty))["status"] == "skipped"


async def test_graph_attaches_basis_and_passes_it_to_the_rewrite(monkeypatch):
    import capstone_agent.review_engine as engine
    from capstone_agent.review_models import TextAssessment, VisualAssessment
    from capstone_agent.review_engine import RewriteDraft, RewriteVerification

    async def ocr(native, tiles, provider):
        return [{"id": "t1", "text": "아토피 치료 크림", "location": {"kind": "image", "fileId": "a", "page": 1, "bbox": None}}], []

    async def fake_search(findings, **kwargs):
        async def fake(name, arguments):
            return {"status": "FOUND", "hits": [hit("제13조 (부당한 표시ㆍ광고 행위 등의 금지)", 0.56)]}
        return await search_basis(findings, call=fake)
    monkeypatch.setattr(engine, "extract_text", ocr)
    monkeypatch.setattr(engine, "search_basis", fake_search)
    prompts = []

    class FakeProvider:
        client = True

        async def parse(self, prompt, schema, **kwargs):
            prompts.append(kwargs.get("text", ""))
            if schema is TextAssessment:
                return schema(findings=[{"phrase": "아토피 치료", "reason": "의약품 오인", "claimType": "drug", "rewrites": []}])
            if schema is RewriteDraft:
                return RewriteDraft(text="민감한 피부를 위한 크림")
            if schema is RewriteVerification:
                return RewriteVerification(passed=True, reasoning="합성")
            return VisualAssessment(findings=[], unreadable=False)

    graph = engine.build_graph([], [], FakeProvider(), InMemorySaver())
    output = await graph.ainvoke({"reviewId": "r", "tenantId": "t", "versionNo": 1, "product": {}},
                                 {"configurable": {"thread_id": "t:r:1"}})
    result = output["result"]
    assert result["retrievalStatus"] == "connected"
    assert result["findings"][0]["basis"][0]["article"].startswith("제13조")
    assert result["standardsSnapshot"][0]["name"] == "화장품법"
    assert any("검색으로 찾은 조문 후보" in w for w in result["warnings"])
    assert any("제13조" in text for text in prompts), "rewrite prompt should carry the retrieved article"
    # The verified, basis-aware rewrite is what Backend stores and the UI shows.
    assert result["findings"][0]["rewrites"] == ["민감한 피부를 위한 크림"]
    assert result["findings"][0]["rewriteVerified"] is True


async def test_vlm_ocr_fills_lines_that_tesseract_dropped(monkeypatch):
    import capstone_agent.review_engine as engine
    from capstone_agent.review_models import OCRLines
    from PIL import Image
    from capstone_shared.documents import tile_image
    tile = tile_image(Image.new("RGB", (64, 64)), "a", "ad.png")[0]

    def tesseract(t):
        return [{"id": "t:ocr:1", "text": "아토피 치료 크림", "location": t.location(method="TESSERACT")}]
    monkeypatch.setattr(engine, "local_ocr", tesseract)

    class FakeProvider:
        client = True

        async def parse(self, prompt, schema, **kwargs):
            return OCRLines(lines=["아토피 치료 크림", "지친 피부 시간을 되돌리는"], unreadable=False)
    blocks, warnings = await engine.extract_text([], [tile], FakeProvider())
    assert [b["text"] for b in blocks] == ["아토피 치료 크림", "지친 피부 시간을 되돌리는"]
    assert blocks[0]["location"]["coordinateSource"] == "TESSERACT" and blocks[1]["location"]["coordinateSource"] == "VLM_OCR_REGION"
    assert warnings == []
