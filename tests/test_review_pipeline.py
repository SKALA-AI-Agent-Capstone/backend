from io import BytesIO
from PIL import Image
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from capstone_shared.documents import tile_image, prepare_document
from capstone_agent.review_engine import build_graph, Provider, deduplicate


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
    assert output["__interrupt__"]
    resumed=await graph.ainvoke(Command(resume="approved"),config)
    assert resumed["reportReady"] is True
    assert resumed["decision"]=="approved"
    assert "runToken" not in resumed


async def test_both_paths_have_independent_results_and_checkpoint_contains_no_pixels(monkeypatch):
    from capstone_agent.review_models import OCRLines,TextAssessment,VisualAssessment
    import capstone_agent.review_engine as engine
    async def ocr(native,tiles,provider):
        return [{"id":"t1","text":"7일 주름 개선","location":{"kind":"image","fileId":"a","page":1,"bbox":None}}],[]
    monkeypatch.setattr(engine,"extract_text",ocr)
    class FakeProvider:
        client=True
        async def parse(self,prompt,schema,**kwargs):
            if schema is TextAssessment:
                return schema(findings=[{"phrase":"없는 원문","reason":"합성","claimType":"wrinkle","rewrites":[]},
                    {"phrase":"주름 개선","reason":"기간에 맞는 자료 확인","claimType":"wrinkle","rewrites":[]}])
            return VisualAssessment(findings=[{"observation":"합성 비교 구성","concern":"촬영 조건 확인","comparisonLimitations":[],"needsAdjacentContext":False}],unreadable=False)
    tile=tile_image(Image.new("RGB",(64,64)),"a","synthetic.png")[0]
    graph=build_graph([], [tile],FakeProvider(),InMemorySaver())
    output=await graph.ainvoke({"reviewId":"r","tenantId":"t","versionNo":1,"product":{}},
                              {"configurable":{"thread_id":"t:r:1"}})
    assert len(output["result"]["findings"])==1
    assert len(output["result"]["visualFindings"])==1
    assert "data:image" not in str(output)
    assert output["result"]["findings"][0]["basis"]==[]


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
