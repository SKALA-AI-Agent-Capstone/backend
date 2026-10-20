#!/usr/bin/env python3
"""Synthetic local integration checks. No customer files or paid model calls."""
import json
import os
import time
import uuid
from io import BytesIO
import httpx
from PIL import Image, ImageDraw, ImageFont


def synthetic_pdf():
    content=b"BT /F1 16 Tf 50 730 Td (SYNTHETIC TEST ONLY 10-1234567) Tj 0 -30 Td (Product evidence sample, not a real clinical report.) Tj ET"
    objects=[b"<< /Type /Catalog /Pages 2 0 R >>",b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",b"<< /Length "+str(len(content)).encode()+b" >>\nstream\n"+content+b"\nendstream"]
    raw=b"%PDF-1.4\n";offsets=[]
    for n, obj in enumerate(objects,1):
        offsets.append(len(raw));raw+=str(n).encode()+b" 0 obj\n"+obj+b"\nendobj\n"
    xref=len(raw);raw+=b"xref\n0 6\n0000000000 65535 f \n"
    raw+=b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets)
    return raw+f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF".encode()


def synthetic_image():
    image=Image.new("RGB",(848,3000),"white")
    draw=ImageDraw.Draw(image)
    font=ImageFont.load_default(size=32)
    for y in (100,1500,2800):
        draw.text((40,y),"SYNTHETIC ADVERTISEMENT ONLY",font=font,fill="black")
    output=BytesIO();image.save(output,format="PNG");return output.getvalue()


def main():
    base=os.getenv("MVP_API_URL","http://127.0.0.1:8080")+"/api/v1"
    client=httpx.Client(base_url=base,timeout=45)
    checks=[];suffix=uuid.uuid4().hex[:10]
    password="Synthetic-Only-Password-2026"
    def call(method,path,token=None,**kwargs):
        headers={"Authorization":f"Bearer {token}"} if token else {}
        response=client.request(method,path,headers=headers,**kwargs)
        return response
    def signup(label):
        response=call("POST","/users/signup",json={"email":f"{label}-{suffix}@example.test","password":password,"name":label,"tenantName":f"Synthetic {label} {suffix}"})
        assert response.status_code==200,("signup",response.status_code,response.text)
        return response.json()
    assert call("GET","/reviews").status_code==401
    admin=signup("admin");other=signup("other");token=admin["accessToken"]
    for role in ("author","reviewer"):
        response=call("POST","/users/team",token,json={"email":f"{role}-{suffix}@example.test","password":password,"name":role,"role":role})
        assert response.status_code==200,response.text
    def login(role):
        response=call("POST","/users/login",json={"email":f"{role}-{suffix}@example.test","password":password})
        assert response.status_code==200,response.text
        return response.json()["accessToken"]
    author=login("author");reviewer=login("reviewer")
    checks.append("server JWT and controlled tenant roles")
    metadata={"product":{"name":"Synthetic-only product","functionalReportNo":"SYNTHETIC"},"media":[],"evidence":[{"docType":"other"}]}
    image=synthetic_image();pdf=synthetic_pdf()
    def upload(path):
        response=call("POST",path,author,data={"metadata":json.dumps(metadata)},files=[
            ("files",("synthetic.png",image,"image/png")),("files",("synthetic.pdf",pdf,"application/pdf")),
            ("evidence",("synthetic-evidence.pdf",pdf,"application/pdf"))])
        assert response.status_code==202,(response.status_code,response.text)
        return response.json()
    review=upload("/reviews");rid=review["id"]
    def wait(no):
        deadline=time.monotonic()+100
        while time.monotonic()<deadline:
            response=call("GET",f"/reviews/{rid}",author)
            assert response.status_code==200,response.text
            doc=response.json();version=doc["versions"][-1]
            if version["no"]==no and version["status"] in {"done","failed"}:
                assert version["status"]=="done",version
                return doc
            time.sleep(1)
        raise AssertionError("Review did not finish within synthetic smoke timeout")
    try:
        review=wait(1);version=review["versions"][-1];result=version["result"]
        assert result["texts"] and any("SYNTHETIC" in text["text"] for text in result["texts"])
        # Without a model key no claim is extracted, so the legal search step has nothing to search.
        expected={"skipped"} if not result["findings"] else {"connected","not_indexed"}
        assert result["retrievalStatus"] in expected and result["legalStatus"]=="not_evaluated",result["retrievalStatus"]
        assert result["visualFindings"]==[] and result["pipelineStatus"]["visual"]=="not_configured"
        assert result["evidence"]["text"] and result["publicData"]["status"]=="error"
        assert result["patents"] and result["patents"][0]["status"]=="error"
        assert "data:image" not in json.dumps(review)
        assert "runToken" not in json.dumps(review)
        checks.append("real PNG and PDF bytes, local OCR, evidence via authenticated MCP")
        assert call("GET",f"/reviews/{rid}",other["accessToken"]).status_code==404
        assert call("POST",f"/reviews/{rid}/decision",author,json={"versionNo":1,"state":"approved","comment":"synthetic"}).status_code==403
        assert call("POST",f"/reviews/{rid}/decision",reviewer,json={"versionNo":1,"state":"approved","comment":""}).status_code==400
        response=call("POST",f"/reviews/{rid}/decision",reviewer,json={"versionNo":1,"state":"approved","comment":"Synthetic manual check; no legal basis was attached."})
        assert response.status_code==200,response.text
        assert response.json()["versions"][-1]["approval"]["agentResume"]=="completed",response.text
        checks.append("tenant isolation, role check, persisted HITL and PostgreSQL resume")
        upload(f"/reviews/{rid}/re-review");review=wait(2)
        assert review["versions"][0]["approval"]["state"]=="approved"
        assert review["versions"][1]["approval"]["state"]=="pending"
        assert call("POST",f"/reviews/{rid}/decision",reviewer,json={"versionNo":1,"state":"approved","comment":"old version"}).status_code==409
        checks.append("new version with independent approval history")
        assert len(call("GET","/reference-sources",token).json()["sources"])==9
        assert call("DELETE","/users/logout",other["accessToken"]).status_code==200
        assert call("GET","/users/me",other["accessToken"]).status_code==401
        checks.append("handoff source manifest and revoked logout token")
    finally:
        response=call("DELETE",f"/reviews/{rid}",author)
        assert response.status_code==200,("cleanup",response.status_code,response.text)
    assert call("GET",f"/reviews/{rid}",author).status_code==404
    checks.append("review deletion")
    print(json.dumps({"checks":checks,"paid_model_calls":0,"customer_files_used":0},ensure_ascii=False,indent=2))


if __name__=="__main__":main()
