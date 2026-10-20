"""Read-only external facts. No invented approval, patent status or legal sources."""
from datetime import datetime, timezone
import asyncio
import os
import re
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

import httpx
from capstone_shared.documents import prepare_document, local_ocr
from capstone_shared.settings import internal_token, required_env


import logging
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)


def now():
    return datetime.now(timezone.utc).isoformat()


def endpoint(name, hostname):
    value=os.getenv(name,"").strip()
    parsed=urlsplit(value)
    if parsed.scheme!="https" or parsed.hostname!=hostname or parsed.query or parsed.fragment:
        return None
    return value


async def functional_report(functional_approval_no, product_name):
    source="식약처 기능성화장품 보고품목정보"
    result={"status":"error","source":source,"checkedAt":now(),"reportNo":functional_approval_no,
            "error":"API_NOT_CONFIGURED","data":None}
    key=os.getenv("MFDS_SERVICE_KEY","")
    url=endpoint("MFDS_REPORT_URL","apis.data.go.kr")
    if not key or not url:
        return result
    try:
        async with httpx.AsyncClient(timeout=12,follow_redirects=False) as client:
            response=await client.get(url,params={"serviceKey":unquote(key),"type":"json","pageNo":1,"numOfRows":100,"item_name":product_name})
            response.raise_for_status()
            data=response.json()
        header=data.get("header",data.get("response",{}).get("header",{}))
        body=data.get("body",data.get("response",{}).get("body",{}))
        if str(header.get("resultCode")) not in {"00","0"}:
            return {**result,"error":"PROVIDER_REJECTED_REQUEST"}
        items=body.get("items",[])
        if isinstance(items,dict):items=items.get("item",[items])
        if isinstance(items,dict):items=[items]
        if not items and int(body.get("totalCount",0))==0:
            return {**result,"status":"not_found","error":None}
        # Identifier mapping is intentionally conservative until an approved sample is verified.
        matching=[i for i in items if str(i.get("ITEM_NAME",""))==product_name and
                  functional_approval_no in [str(i.get(k,"")) for k in ("COSMETIC_REPORT_SEQ","DEPT_RECEIPT_NO")]]
        if len(matching)!=1:
            return {**result,"status":"ambiguous","error":"IDENTIFIER_MAPPING_REQUIRES_CONFIRMATION"}
        row=matching[0]
        return {**result,"status":"found","error":None,"data":{"productName":row.get("ITEM_NAME"),
            "functions":[],"efficacyText":row.get("EE_DOC_DATA",""),"reportedAt":row.get("REPORT_DATE"),
            "note":"효능 범위 필드는 실제 응답 검증 전이므로 자동 확정하지 않습니다."}}
    except Exception:
        return {**result,"error":"PROVIDER_REQUEST_FAILED"}


def parse_patents(xml,number,source):
    result={"id":number,"number":number,"status":"unavailable","source":source,"checkedAt":now(),
            "match":{"status":"unknown","verdict":"unknown","summary":"청구항 원문 대조는 이번 최소 기능에 포함되지 않습니다.","reason":"청구항 원문 미확보"},
            "data":None,"error":None}
    root=ET.fromstring(xml)
    success=root.findtext(".//successYN")
    code=root.findtext(".//resultCode")
    if success!="Y" or (code and code not in {"00","0"}):
        return {**result,"status":"error","error":"PROVIDER_REJECTED_REQUEST"}
    digits=re.sub(r"\D","",number)
    matched=[]
    for item in root.findall(".//item"):
        raw=item.findtext("registerNumber","")
        normalized=re.sub(r"\D","",raw)
        # Seven-digit registration body versus the official kind/suffix representation.
        exact=normalized==digits or (len(digits)==9 and normalized==digits+"0000")
        if exact:matched.append(item)
    if len(matched)!=1:
        return {**result,"error":"NO_EXACT_MATCH_OR_INCOMPLETE_SEARCH"}
    item=matched[0];state=item.findtext("registerStatus","")
    return {**result,"status":"registered" if state=="등록" else "unavailable",
        "data":{"title":item.findtext("inventionTitle",""),"inventionTitle":item.findtext("inventionTitle",""),
            "abstract":item.findtext("astrtCont",""),"registerStatus":state,"registerNumber":item.findtext("registerNumber",""),
            "applicant":item.findtext("applicantName",""),"registerDate":item.findtext("registerDate",""),"ipc":item.findtext("ipcNumber","")}}


async def patents(numbers):
    result=[];source="KIPRIS Plus getWordSearch"
    key=os.getenv("KIPRIS_SERVICE_KEY","")
    url=endpoint("KIPRIS_WORD_SEARCH_URL","plus.kipris.or.kr")
    for number in numbers[:10]:
        base={"id":number,"number":number,"status":"error","source":source,"checkedAt":now(),"data":None,
              "error":"API_NOT_CONFIGURED","match":{"status":"unknown","verdict":"unknown","summary":"원문 대조 미실행","reason":"원문 대조 미실행"}}
        if not re.fullmatch(r"10-\d{7}",number):
            result.append({**base,"status":"needs_confirm","error":"NUMBER_REQUIRES_CONFIRMATION"});continue
        if not key or not url:
            result.append(base);continue
        try:
            async with httpx.AsyncClient(timeout=12,follow_redirects=False) as client:
                response=await client.get(url,params={"word":re.sub(r"\D","",number),"year":0,"patent":"true","utility":"false","numOfRows":100,"pageNo":1,"ServiceKey":unquote(key)})
                response.raise_for_status()
            result.append(parse_patents(response.content,number,source))
        except Exception:
            result.append({**base,"error":"PROVIDER_REQUEST_FAILED"})
    return result


async def structure(tenant_id,review_id,documents,version_no,run_token):
    texts=[];failures=[]
    async with httpx.AsyncClient(timeout=20) as client:
        for doc in documents[:10]:
            try:
                response=await client.get(required_env("BACKEND_BASE_URL")+f"/internal/reviews/{review_id}/assets/{doc['id']}",
                    params={"versionNo":version_no},headers={"Authorization":f"Bearer {internal_token()}","X-Run-Token":run_token})
                response.raise_for_status()
                native,tiles=await asyncio.to_thread(prepare_document,response.content,doc)
                blocks=list(native)
                for tile in tiles:
                    try:blocks.extend(await asyncio.to_thread(local_ocr,tile))
                    except Exception:failures.append({"documentId":doc["id"],"code":"OCR_UNAVAILABLE","page":tile.page})
                texts.extend(t["text"] for t in blocks)
            except Exception:
                failures.append({"documentId":doc.get("id"),"code":"DOCUMENT_UNREADABLE_OR_EXPIRED"})
    return {"status":"partial" if failures else "extracted","text":"\n".join(texts)[:60000],
            "failures":failures,"claimTypes":[],"requirements":[],"flags":[],
            "note":"본문 추출 결과이며 제품 효능과의 일치 판정은 아닙니다."}
