from io import BytesIO
from PIL import Image
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from capstone_shared.documents import tile_image, prepare_document
from capstone_agent.review_engine import (build_graph, Provider, deduplicate, correct_ocr_text, OcrCorrection,
    tile_manifest, assign_tile)


async def test_ocr_correction_fixes_broken_spacing_without_changing_line_count():
    class FakeProvider:
        async def parse(self,prompt,schema,**kwargs):
            return OcrCorrection(lines=["7일 주름 개선","SPF50 PA++++"])
    blocks=[{"id":"a","text":"7 일 주 름 개 선","location":{}},{"id":"b","text":"SPF50 PA++++","location":{}}]
    fixed=await correct_ocr_text(FakeProvider(),blocks)
    assert [b["text"] for b in fixed]==["7일 주름 개선","SPF50 PA++++"]
    assert fixed[0]["id"]=="a" and fixed[1]["id"]=="b"  # location/id survive, only text replaced


async def test_ocr_correction_falls_back_to_raw_text_on_line_count_mismatch():
    class BadProvider:
        async def parse(self,prompt,schema,**kwargs):
            return OcrCorrection(lines=["합쳐버린 한 줄"])
    blocks=[{"id":"a","text":"원문 1","location":{}},{"id":"b","text":"원문 2","location":{}}]
    result=await correct_ocr_text(BadProvider(),blocks)
    assert result is blocks


def test_long_image_tiles_cover_original_without_gaps():
    image=Image.new("RGB",(848,19799),"white")
    tiles=tile_image(image,"synthetic","synthetic.png")
    assert len(tiles)==15
    assert tiles[0].box[1]==0 and tiles[-1].box[3]==19799
    assert all(a.box[3]>b.box[1] for a,b in zip(tiles,tiles[1:]))
    assert tiles[1].location()["region"][1]==1360/19799
    assert all(tile.location()["bbox"] is None for tile in tiles)


def test_input_is_decoded_from_bytes_not_filename():
    output=BytesIO();Image.new("RGB",(64,64),"white").save(output,format="PNG")
    blocks,tiles=prepare_document(output.getvalue(),{"id":"a","name":"misleading.pdf"})
    assert blocks==[] and len(tiles)==1


def test_overlap_dedup_does_not_remove_separate_repeated_ad_claims():
    def block(y):
        return {"text":"같은 문구","location":{"fileId":"a","page":1,"bbox":[0,y,1,y+0.1]}}
    assert len(deduplicate([block(0),block(0.05),block(0.8)]))==2


def test_tile_manifest_keeps_coordinates_only():
    tiles=tile_image(Image.new("RGB",(64,3000)),"a","long.png")
    manifest=tile_manifest(tiles)
    assert [t["id"] for t in manifest]==[t.id for t in tiles]
    assert [t["index"] for t in manifest]==[0,1,2]
    assert manifest[1]=={"id":"a:p1:t1","index":1,"fileId":"a","fileName":"long.png","page":1,
                         "region":[0.0,1360/3000,1.0,2960/3000]}


def test_overlap_claim_belongs_to_the_tile_whose_center_is_nearest():
    # Tiles: t0 0~1600, t1 1360~2960, t2 2720~3000. 1360~1600 is shared by t0 and t1.
    manifest=tile_manifest(tile_image(Image.new("RGB",(64,3000)),"a","long.png"))
    def at(y0,y1,tile_id=None):
        return {"fileId":"a","page":1,"tileId":tile_id,"bbox":[0,y0/3000,1,y1/3000]}
    assert assign_tile(at(100,140),manifest)=="a:p1:t0"
    # OCR read this line in t1 but it sits closer to t0's center; one owner, not two.
    assert assign_tile(at(1400,1440,"a:p1:t1"),manifest)=="a:p1:t0"
    assert assign_tile(at(1560,1590,"a:p1:t0"),manifest)=="a:p1:t1"
    assert assign_tile(at(2900,2950),manifest)=="a:p1:t2"


def test_region_only_and_unmatched_locations():
    manifest=tile_manifest(tile_image(Image.new("RGB",(64,3000)),"a","long.png"))
    region_only={"fileId":"a","page":1,"tileId":"a:p1:t2","bbox":None,"region":manifest[2]["region"]}
    assert assign_tile(region_only,manifest)=="a:p1:t2"
    # PDF native text has a page box and no tile.
    assert assign_tile({"fileId":"a","page":1,"bbox":[0,0.5,1,0.51]},manifest)=="a:p1:t1"
    assert assign_tile({"fileId":"b","page":1,"bbox":[0,0.5,1,0.51]},manifest) is None
    assert assign_tile({"fileId":"a","page":1,"tileId":"gone","bbox":None},manifest) is None
    assert assign_tile(None,manifest) is None


async def test_missing_provider_does_not_invent_findings_or_sources(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY",raising=False)
    graph=build_graph([],[],Provider(),InMemorySaver())
    config={"configurable":{"thread_id":"tenant:synthetic:1"}}
    output=await graph.ainvoke({"reviewId":"r","tenantId":"t","versionNo":1,"product":{}},config)
    result=output["result"]
    assert result["findings"]==[] and result["visualFindings"]==[]
    assert result["pipelineStatus"]=={"text":"not_configured","visual":"not_configured"}
    assert result["legalStatus"]=="not_evaluated"
    assert result["standardsSnapshot"]==[]
    assert result["violations"]==[]
    assert output["__interrupt__"]
    resumed=await graph.ainvoke(Command(resume="approved"),config)
    assert resumed["reportReady"] is True
    assert resumed["decision"]=="approved"
    assert "runToken" not in resumed


async def test_both_paths_have_independent_results_and_checkpoint_contains_no_pixels(monkeypatch):
    from capstone_agent.review_models import TextAssessment,VisualAssessment
    import capstone_agent.review_engine as engine
    from capstone_agent.review_engine import RewriteDraft,RewriteVerification
    async def ocr(native,tiles,provider):
        return [{"id":"t1","text":"7일 주름 개선","location":{"kind":"image","fileId":"a","page":1,"bbox":None}}],[]
    monkeypatch.setattr(engine,"extract_text",ocr)
    class FakeProvider:
        client=True
        async def parse(self,prompt,schema,**kwargs):
            if schema is TextAssessment:
                return schema(findings=[{"phrase":"없는 원문","reason":"합성","claimType":"wrinkle","rewrites":[]},
                    {"phrase":"주름 개선","reason":"기간에 맞는 자료 확인","claimType":"wrinkle","rewrites":[]}])
            if schema is RewriteDraft:
                return RewriteDraft(text="자료 확인 중인 주름 개선 효과")
            if schema is RewriteVerification:
                return RewriteVerification(passed=True,reasoning="합성 재검증 통과")
            return VisualAssessment(findings=[{"observation":"합성 비교 구성","concern":"촬영 조건 확인","comparisonLimitations":[],"needsAdjacentContext":False}],unreadable=False)
    tile=tile_image(Image.new("RGB",(64,64)),"a","synthetic.png")[0]
    graph=build_graph([], [tile],FakeProvider(),InMemorySaver())
    output=await graph.ainvoke({"reviewId":"r","tenantId":"t","versionNo":1,"product":{}},
                              {"configurable":{"thread_id":"t:r:1"}})
    assert len(output["result"]["findings"])==1
    assert len(output["result"]["visualFindings"])==1
    assert [t["id"] for t in output["result"]["tiles"]]==[tile.id]
    assert output["result"]["visualFindings"][0]["tileId"]==tile.id
    assert output["result"]["findings"][0]["tileId"] is None  # synthetic OCR block has no box or tile
    assert "data:image" not in str(output)
    assert output["result"]["findings"][0]["basis"]==[]
    violations=output["result"]["violations"]
    assert len(violations)==1
    assert violations[0]["text_excerpt"]=="주름 개선"
    assert violations[0]["rewrite_suggestion"]=="자료 확인 중인 주름 개선 효과"
    assert violations[0]["rewrite_attempt_count"]==1
    assert violations[0]["rewrites"]==[{"attempt_no":1,"text":"자료 확인 중인 주름 개선 효과",
        "requested_by_human":False,"retry_note":None}]
    assert violations[0]["final_resolution"] is None and violations[0]["final_text_validated"] is False


async def test_rewrite_retry_limit_stops_auto_suggestions_for_manual_input(monkeypatch):
    from capstone_agent.review_models import TextAssessment,VisualAssessment
    import capstone_agent.review_engine as engine
    from capstone_agent.review_engine import RewriteDraft,RewriteVerification,REWRITE_RETRY_LIMIT
    async def ocr(native,tiles,provider):
        return [{"id":"t1","text":"평생 보습 지속","location":{"kind":"image","fileId":"a","page":1,"bbox":None}}],[]
    monkeypatch.setattr(engine,"extract_text",ocr)
    attempts=[]
    class NeverPassingProvider:
        client=True
        async def parse(self,prompt,schema,**kwargs):
            if schema is TextAssessment:
                return schema(findings=[{"phrase":"평생 보습 지속","reason":"절대적 표현","claimType":"absolute","rewrites":[]}])
            if schema is RewriteDraft:
                attempts.append(1)
                return RewriteDraft(text=f"시도 {len(attempts)}")
            if schema is RewriteVerification:
                return RewriteVerification(passed=False,reasoning="여전히 절대적 표현")
            return VisualAssessment(findings=[],unreadable=False)
    graph=build_graph([],[],NeverPassingProvider(),InMemorySaver())
    output=await graph.ainvoke({"reviewId":"r","tenantId":"t","versionNo":1,"product":{}},
                              {"configurable":{"thread_id":"t:r:2"}})
    violation=output["result"]["violations"][0]
    assert violation["rewrite_attempt_count"]==REWRITE_RETRY_LIMIT==3
    assert len(violation["rewrites"])==REWRITE_RETRY_LIMIT
    assert violation["final_resolution"] is None  # 사람의 rewritten_manual 입력이 필요하다


async def test_verify_rewrite_checks_source_agnostically_and_does_not_rewrite():
    from capstone_agent.review_engine import verify_rewrite
    from capstone_agent.review_models import RewriteVerification
    seen = {}
    class FakeProvider:
        async def parse(self, prompt, schema, **kwargs):
            seen["schema"], seen["text"] = schema, kwargs["text"]
            return RewriteVerification(passed=False, reasoning="여전히 절대적 표현")
    result = await verify_rewrite(FakeProvider(), "평생 지속되는 보습", "절대적 표현")
    assert seen["schema"] is RewriteVerification
    assert "평생 지속되는 보습" in seen["text"] and "절대적 표현" in seen["text"]
    assert result.passed is False and result.reasoning


def test_patent_error_envelope_is_not_no_results():
    from capstone_business_mcp.review_tools import parse_patents
    result=parse_patents("<response><header><successYN>N</successYN><resultCode>30</resultCode></header></response>","10-1234567","synthetic")
    assert result["status"]=="error"


async def test_public_api_configuration_is_not_a_product_absence(monkeypatch):
    from capstone_business_mcp.review_tools import functional_report
    monkeypatch.delenv("MFDS_SERVICE_KEY",raising=False)
    result=await functional_report("synthetic","합성 제품")
    assert result["status"]=="error" and result["error"]=="API_NOT_CONFIGURED"


def test_source_preview_preserves_original_spans_and_page_boundaries():
    from capstone_rag_mcp.preview import chunk_pages
    pages=['제1조 본문\n'+'조건과 예외를 함께 확인한다.\n'*100, '제2조 별도 페이지']
    chunks=chunk_pages(pages)
    assert all(c['body']==pages[c['page']-1][c['start']:c['end']] for c in chunks)
    first=[c for c in chunks if c['page']==1]
    assert first[0]['start']==0 and first[-1]['end']==len(pages[0])
    assert all(a['end']>b['start'] for a,b in zip(first,first[1:]))
    assert chunks[-1]['page']==2


def test_public_functional_fields_never_invent_unknown_scope():
    from capstone_business_mcp.review_tools import functional_item
    data=functional_item({'ITEM_NAME':'합성 제품','EFFECT_YN1':'Y','EFFECT_YN2':'N','EFFECT_YN3':'unknown','SPF':'50+'})
    assert data['functions']==['미백']
    assert data['spf']=='50+'
    assert data['scopeFields']['EFFECT_YN3']=='unknown'


async def test_functional_lookup_uses_official_operation_and_catches_cancelled_items(monkeypatch):
    import httpx
    import capstone_business_mcp.review_tools as tools
    original=httpx.AsyncClient
    def handler(request):
        assert request.url.path.endswith('/FtnltCosmRptPrdlstInfoService01/getRptPrdlstInq01')
        assert request.url.params['cosmetic_report_seq']=='123'
        return httpx.Response(200,json={'header':{'resultCode':'00'},'body':{'totalCount':1,'items':[
            {'ITEM_NAME':'합성 제품','COSMETIC_REPORT_SEQ':'123','CANCEL_APPROVAL_YN':'Y'}]}})
    monkeypatch.setenv('MFDS_SERVICE_KEY','synthetic-test-key')
    monkeypatch.delenv('MFDS_REPORT_URL',raising=False)
    monkeypatch.setattr(tools.httpx,'AsyncClient',lambda **kw:original(transport=httpx.MockTransport(handler),**kw))
    result=await tools.functional_report('123','합성 제품')
    assert result['status']=='cancelled' and result['error']=='REPORTED_ITEM_CANCELLED'


async def test_functional_lookup_does_not_treat_truncated_pages_as_product_absence(monkeypatch):
    import httpx
    import capstone_business_mcp.review_tools as tools
    original=httpx.AsyncClient
    pages=[]
    def handler(request):
        pages.append(request.url.params['pageNo'])
        return httpx.Response(200,json={'header':{'resultCode':'00'},'body':{'totalCount':1000,'items':[
            {'ITEM_NAME':'다른 제품','COSMETIC_REPORT_SEQ':'987'}]}})
    monkeypatch.setenv('MFDS_SERVICE_KEY','synthetic-test-key')
    monkeypatch.delenv('MFDS_REPORT_URL',raising=False)
    monkeypatch.setattr(tools.httpx,'AsyncClient',lambda **kw:original(transport=httpx.MockTransport(handler),**kw))
    result=await tools.functional_report('123','합성 제품')
    assert pages==['1','2','3']
    assert result['status']=='ambiguous'


async def test_human_rewrite_prompt_carries_previous_suggestions_and_note():
    from capstone_agent.review_engine import rewrite_phrase
    from capstone_agent.review_models import RewriteRequest, RewriteSuggestion
    seen = {}
    class FakeProvider:
        client = True
        async def parse(self, prompt, schema, **kwargs):
            seen["schema"], seen["text"] = schema, kwargs["text"]
            return schema(text="  주름 개선 기능성 보고 범위 안의 표현  ")
    request = RewriteRequest(reviewId="r", tenantId="t", phrase="7일 주름 개선", reason="기간 자료 확인",
        previousRewrites=["첫 제안"], retryNote="더 짧게")
    assert await rewrite_phrase(FakeProvider(), request) == "주름 개선 기능성 보고 범위 안의 표현"
    assert seen["schema"] is RewriteSuggestion
    assert "첫 제안" in seen["text"] and "더 짧게" in seen["text"] and "7일 주름 개선" in seen["text"]
