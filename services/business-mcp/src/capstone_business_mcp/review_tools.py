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


def endpoint(name, hostname, default=""):
    value=os.getenv(name,"").strip() or default
    parsed=urlsplit(value)
    if parsed.scheme!="https" or parsed.hostname!=hostname or parsed.query or parsed.fragment:
        return None
    return value


def normalize_items(body):
    items=body.get("items",[])
    if isinstance(items,dict):items=items.get("item",[items])
    if isinstance(items,dict):items=[items]
    if not isinstance(items,list):raise ValueError("INVALID_ITEMS")
    return items


def functional_item(row):
    functions=[label for field,label in [("EFFECT_YN1","미백"),("EFFECT_YN2","주름개선"),("EFFECT_YN3","자외선차단")]
               if str(row.get(field,"")).strip().upper()=="Y"]
    return {"productName":row.get("ITEM_NAME"),"reportSequence":row.get("COSMETIC_REPORT_SEQ"),
            "receiptNo":row.get("DEPT_RECEIPT_NO"),"functions":functions,"reportedAt":row.get("REPORT_DATE"),
            "efficacyText":row.get("EE_DOC_DATA", ""),"spf":row.get("SPF"),"pa":row.get("PA"),
            "manufacturer":row.get("MANUF_NAME"),"company":row.get("ENTP_NAME"),
            "scopeFields":{key:row.get(key) for key in ["EFFECT_YN1","EFFECT_YN2","EFFECT_YN3","EE_CODE","EE_NAME","WATER_PROOFING_FLAG"]},
            "note":"공식 보고품목의 필드를 반환한 보조 정보이며 광고 적법성이나 시험자료 충족 판정은 아닙니다."}


async def functional_report(functional_approval_no, product_name):
    source="식약처 기능성화장품 보고품목정보"
    result={"status":"error","source":source,"checkedAt":now(),"reportNo":functional_approval_no,
            "error":"API_NOT_CONFIGURED","data":None}
    key=os.getenv("MFDS_SERVICE_KEY","")
    url=endpoint("MFDS_REPORT_URL","apis.data.go.kr",
        "https://apis.data.go.kr/1471000/FtnltCosmRptPrdlstInfoService01/getRptPrdlstInq01")
    if not key or not url:return result
    try:
        matches=[];total=0;seen=0
        async with httpx.AsyncClient(timeout=12,follow_redirects=False) as client:
            for page in range(1,4):
                params={"serviceKey":unquote(key),"type":"json","pageNo":page,"numOfRows":100,"item_name":product_name}
                if re.fullmatch(r"[0-9]+",functional_approval_no):params["cosmetic_report_seq"]=functional_approval_no
                response=await client.get(url,params=params)
                response.raise_for_status();data=response.json()
                envelope=data.get("response",data)
                if str(envelope.get("header",{}).get("resultCode")) not in {"00","0"}:
                    return {**result,"error":"PROVIDER_REJECTED_REQUEST"}
                body=envelope.get("body",{});items=normalize_items(body)
                total=int(body.get("totalCount",0));seen+=len(items)
                matches.extend(row for row in items if str(row.get("ITEM_NAME","")).strip()==product_name.strip() and
                    functional_approval_no.strip() in [str(row.get(k,"")).strip() for k in ("COSMETIC_REPORT_SEQ","DEPT_RECEIPT_NO")])
                if seen>=total or not items:break
        if seen==0 and total==0:return {**result,"status":"not_found","error":None}
        if len(matches)!=1 or seen<total:
            return {**result,"status":"ambiguous","error":"IDENTIFIER_MAPPING_OR_PAGINATION_REQUIRES_CONFIRMATION"}
        row=matches[0]
        if str(row.get("CANCEL_APPROVAL_YN","")).upper()=="Y":
            return {**result,"status":"cancelled","error":"REPORTED_ITEM_CANCELLED","data":functional_item(row)}
        return {**result,"status":"found","error":None,"data":functional_item(row)}
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
