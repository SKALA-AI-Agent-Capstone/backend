# RAG 담당 시작점

개발 목표는 [노션 MCP 명세](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-1-3ff616ab8fec804eba66e8a7b2bd99e8)의
search_regulations와 search_precedents다. 기존 기반의 이름을 목표 API처럼 사용하지 않는다.
공통 인터페이스와 미확정 항목은 docs/03-contracts.md에 모았다.

## 2026-10-21 근거 수집과 검토 흐름 (feat/review-evidence-pipeline)

이 절이 아래 2026-10-20 절보다 우선한다.

### 출처와 수집 경로

| 출처 | 경로 | 적재 단위 |
| --- | --- | --- |
| S05 화장품법 법률, 시행령, 시행규칙 | legalize-kr. GitHub가 막히면 법제처 API 현행본(lawapi:화장품법/*) | 조문 |
| S06 시행규칙 별표 5 | 법제처 API 법령 본문의 별표 | 호와 목 (별표 5 제2호 가목 등) |
| S07 표시광고 실증 규정 | 법제처 API 행정규칙 | 조문, 별표는 표현별 행 |
| S08 기능성화장품 심사 규정 | 법제처 API 행정규칙 | 조문과 별표 4 |
| S09 표시광고법 | 법제처 API 법령 | 조문 |
| S10 화장품 안전기준 등에 관한 규정 (신규) | 법제처 API 행정규칙 | 조문, 별표 1(사용 금지)과 별표 2(사용 제한) |
| S01 관리 지침, S02 협회 기준, S03 시험방법 | PDF (없으면 공식 주소에서 내려받음) | 문서 자체의 제목(Ⅰ., 1., [별표]) 단위 |
| S04 2026 지침과 위반사례 | PDF, 2~7쪽 rules, 8~27쪽 cases | 슬라이드. 사례 본문은 이미지라 제목 위주 |

```sh
uv run --all-packages --frozen python scripts/ingest_rag.py all      # 호스트에서 전체
docker compose run --rm rag-ingest                                   # 컨테이너에서 (knowledge가 비었을 때만)
```

- compose를 올리면 rag-ingest가 한 번 돌고, knowledge.chunks가 비어 있을 때만 전체를 적재한다. LAW_OC가 있어야 법제처 경로가 동작한다.
- 같은 문서를 다시 받으면 버전 전체의 시행 기간을 다시 계산한다. 검색은 검토일에 시행 중인 버전만 본다.
- knowledge.article_links에 조문 사이의 인용, 위임(implements), 별표 연결을 저장한다. 인용 대상이 8개를 넘는 허브 조문(권한의 위임, 벌칙)은 뺀다.

### 규칙표 (services/rag-mcp/src/capstone_rag_mcp/claim_rules.json)

주장 유형(claimType)이나 문구 패턴으로 규칙이 걸리면, 정해진 현행 조항과 실행할 대조가 붙는다. 검색 운에 맡기지 않는다.

| 규칙 | 조항 | 대조 |
| --- | --- | --- |
| drug 의약품 오인 | 법 제13조 제1항제1호, 별표 5 제2호 가목, 지침 별표 1 | |
| functional 기능성 효능 | 법 제13조 제1항제2호, 별표 5 제2호 나목, 지침 별표 2 | 기능성 보고품목 |
| official_endorsement 식약처, 전문가, 인증 | 별표 5 제2호 다목과 사목, 법 제13조 제1항제2호 | 기능성 보고품목 |
| substantiation 수치, 시험, 임상 | 법 제14조, 시행규칙 제23조, 실증 규정 제3조와 제4조, 실증 별표 행 | 실증자료 제출 |
| absolute_comparison 절대, 비교 | 별표 5 제2호 바목과 사목 | |
| unverifiable_effect 효능 과장 | 법 제13조 사실과 다른 표현 호, 별표 5 제2호 사목과 아목, 실증 별표 행 | |
| ingredient, human_derived 성분 | 지침 별표 2 '무(無)' 표현, 추출물 함량 기준, 별표 5 사목 | 안전기준 별표 1, 2와 식약처 규제정보 |
| gmp, patent, natural_organic | 별표 5 사목과 아목, 지침 천연 지수 기준 | GMP 업체 목록, 특허 번호 |

- 호 번호는 하드코딩하지 않고 현행 조문 본문에서 item_hint 문구로 찾는다. 개정으로 호가 바뀌어도 맞는 호를 가리킨다.
- sweep=true 규칙은 OCR 전체 줄에도 적용해, 모델이 뽑지 않은 고위험 줄을 문구로 보충한다(source=rule_sweep).
- 규칙에 걸렸다는 것은 위반 판정이 아니라 담당자가 볼 근거 후보라는 뜻이다. 규칙을 바꾸면 version을 올린다.

### 대조 결과 (finding.checks)

| 대조 | 상태 |
| --- | --- |
| functional_report | consistent, mismatch(보고 기능과 다른 효능), not_found, ambiguous, unavailable |
| substantiation | evidence_missing, evidence_submitted(내용 대조는 담당자) |
| ingredient | prohibited, restricted, not_listed, needs_input |
| gmp | gmp_listed, gmp_not_listed, needs_input(제조업체 모름) |
| patent | needs_input(번호 없음), evidence_submitted |

attention=true인 상태는 담당자가 확인할 항목이다. 어떤 상태도 자동 위반 판정이 아니다.

### 알려진 한계

- 검색은 키워드(BM25)와 벡터(OpenAI text-embedding-3-small, 1024차원)를 합친 하이브리드다. 근거와 비교는 docs/12-hybrid-retrieval.md에 있다.
- S04 사례 쪽은 이미지 슬라이드라 search_precedents가 제목 수준만 찾는다.
- 성분 대조는 고시에 적힌 이름과 흔한 표기 차이(하이드로/히드로 등)만 맞춘다. 동의어 사전은 없다.
- 실증자료는 제출 여부만 본다. 증빙 내용이 해당 표현을 뒷받침하는지는 담당자가 대조한다.
- 리라이트 재검증이 수치를 키운 문안("20.48% 더 좋아진")을 통과시키는 경우가 있다(Agent 담당 과제).

## 2026-10-20 수집 경로 변경

노션 참고자료 9건 중 화장품법은 [legalize-kr](https://github.com/legalize-kr/legalize-kr/tree/main/kr/%ED%99%94%EC%9E%A5%ED%92%88%EB%B2%95)에서, 나머지는 PDF로 적재한다.
사용 API 4종은 색인하지 않고 business-mcp 도구로 두어 Agent가 필요할 때 고른다.
각 출처의 경로는 reference-sources.json의 acquisition 필드가 원본이다.

| 출처 | 경로 | 비고 |
| --- | --- | --- |
| S05 화장품법 제13조, 제14조 | legalize-kr | 법률, 시행령, 시행규칙 세 파일 전체를 조문 단위로 적재 |
| S06 시행규칙 별표 5 | legalize-kr + PDF | 시행규칙 조문은 legalize-kr, 별표 5 표 본문은 Markdown에 없어 PDF |
| S01 관리 지침, S03 실증 시험방법 | PDF | 식약처 안내서 |
| S02 협회 광고자문 기준 | PDF | 협회 기준, 법령과 같은 효력으로 취급하지 않음 |
| S04 2026 지침 및 위반사례 | PDF | 규정과 사례 페이지 범위(page_ranges)를 적기 전까지 적재하지 않음 |
| S07 실증 규정, S08 기능성 심사 규정 | PDF | 식약처 고시. legalize-kr는 법률, 시행령, 시행규칙만 다룸 |
| S09 표시광고법 제3조 | PDF | legalize-kr에도 있어 method만 바꾸면 전환 가능 |

```sh
GITHUB_TOKEN=... uv run --all-packages --frozen python scripts/ingest_rag.py legalize   # 화장품법
uv run --all-packages --frozen python scripts/ingest_rag.py pdf                          # data/sources/S0x.pdf
```

- legalize-kr는 개정마다 공포일자 커밋이 있다. 수집일에 시행 중인 버전과 그 뒤에 공포된 미시행 버전을 함께 넣고, effective_from과 effective_until로 구분한다.
- 같은 문서 키, 버전, 해시는 다시 쓰지 않는다. 새 버전이 오면 이전 버전의 effective_until만 조정한다.
- legalize-kr 본문은 공포본 기준이라, 부칙에서 시행일을 늦춘 일부 조문이 섞일 수 있다. 부칙과 조문별 시행일 처리는 후속이다.
- legalize-kr는 force-push로 커밋 해시가 바뀔 수 있다고 공지한다. 버전 식별은 공포일자와 공포번호로 하고 해시는 storage_key의 참고값이다.
- GitHub 비인증 API 한도는 시간당 60회다. GITHUB_TOKEN은 공개 읽기 권한이면 충분하며 .env에만 둔다.
- 검색은 임베딩이 없어도 문자 bigram 키워드 순위로 동작한다. RAG_EMBEDDING_MODEL(기본 text-embedding-3-small)로 임베딩하면 벡터 순위와 RRF로 합친다. `batch embed`가 이미 적재된 조각만 임베딩한다.

## RAG 연동 현황과 브랜치 간 통합 가이드 (2026-10-20 기준)

Agent, Frontend, Backend 담당이 RAG를 붙일 때 이 절만 읽으면 되도록 정리했다.
RAG 계약(packages/shared/src/capstone_shared/rag_contracts.py, contracts/search-*.output.schema.json)이 유지되는 한
이후 RAG 내부 개선(PDF 적재, 임베딩, 순위 조정)은 다른 브랜치와 충돌하지 않는다.

### 1. 지금 동작하는 것 (feat/rag-legal-sources)

| 구성 | 상태 | 확인 근거 |
| --- | --- | --- |
| 화장품법 적재 | 법률 4개 버전, 시행령 1개, 시행규칙 1개, 조문 315개 | 새 clone에서 ingest_rag.py legalize 실행 후 knowledge 행 수 |
| rag-mcp search_regulations | 등록, 인증된 HTTP 호출로 FOUND 응답 | 아래 6번 확인 코드 |
| rag-mcp search_precedents | 등록, 사례 미적재라 CORPUS_NOT_INDEXED | 같은 코드 |
| v0.1 search_rules, search_cases, get_evidence | 그대로 유지, 여전히 NOT_IMPLEMENTED | 기존 consumer 호환용 |
| business-mcp API 도구 4개 | verify_functional_approval, verify_patent, verify_gmp_company, lookup_ingredient_regulation | 키가 없으면 API_NOT_CONFIGURED |
| PDF 출처 8건 | 수집기만 있음, 원문 미적재 | ingest_rag.py pdf가 PDF_NOT_FOUND로 건너뜀 |

### 2. Agent와 화면 연결 (feat/rag-agent-connect에서 해결)

처음에는 main의 Agent가 rag-mcp를 호출하지 않고 retrievalStatus="not_connected"를 고정했으며,
Frontend 문구도 상태와 무관하게 "법령 검색 미연결"로 고정돼 있었다. 다음과 같이 연결했다.

- Agent: capstone_agent/regulations.py와 그래프의 regulations 노드(text_path, photo_path 뒤, business 앞).
- 리라이트: 조문 후보를 프롬프트에 넣고, 재검증을 통과한 문안이 finding.rewrites로 저장된다.
- OCR: Tesseract가 누락한 줄을 VLM OCR로 보충한다.
- Backend: /api/v1/reference-sources가 rag-mcp /health/corpus의 실제 적재 목록을 전달한다.
- Frontend: shared/constants/legal.js의 상태별 문구로 결과, 승인, 리포트, 출처 화면을 표시한다.

### 3. 다른 브랜치가 맞출 계약

#### Agent (구현: capstone_agent/regulations.py)

PR #6에서 tool_loop 스캐폴드가 정리되어 regulations.py가 호출을 맡는다. 호출 형식은 아래와 같고 인자는 query로 감싸지 않는다.

```python
result = await call_rag_tool("search_regulations", {
    "query_text": claim["claim_text"],      # 주장 문구와 짧은 문맥. OCR 띄어쓰기는 검색 시 무시된다
    "top_k": 5,                              # 1~10
    "review_date": "2026-10-20",             # 선택. 생략하면 오늘, 그날 시행 중인 법령 버전만 찾는다
})
content = result.structured_content
```

_TRANSITIONAL_TOOL 값도 "rag.search_regulations"로 바꿔 guardrail 집계가 같은 이름을 보게 한다.
사례는 search_precedents(query_text, top_k=3)를 따로 부르고 법령 근거와 섞지 않는다.

응답 status별 처리:

| status | 의미 | Agent 처리 제안 |
| --- | --- | --- |
| FOUND | hits에 조문이 있음 | hits를 finding.basis로 옮김 |
| NO_RELEVANT_EVIDENCE | 검색했으나 맞는 조문 없음 | basis는 비우고 "근거 미발견"으로 표시. 적합 판정이 아님 |
| CORPUS_NOT_INDEXED | 해당 날짜에 적재된 자료 없음 | 검색하지 않은 것과 같음. retrievalStatus를 실패로 |
| SEARCH_ERROR | DB 장애 | 같은 도구 재시도 정책 적용 후 실패면 "확인 불가" |

RegulationHit를 화면의 basis 형식(frontend shared/components/review/BasisItem.vue)으로 옮기는 매핑 제안:

| basis 필드 (Frontend) | RegulationHit 필드 | 비고 |
| --- | --- | --- |
| sourceName | source | 예: 화장품법, 화장품법 시행규칙 |
| article | section | 예: 제13조 (부당한 표시ㆍ광고 행위 등의 금지). PDF는 section이 없으므로 page로 "p.N" |
| version | version | 공포일자와 공포번호. 예: 2026-04-07 제21525호 |
| revisedAt | effective_from | 시행일 |
| original | excerpt | 저장 원문 그대로. 요약하거나 바꾸지 않음 |
| plain | (없음) | 쉬운 설명은 Agent가 작성. RAG는 만들지 않음. 없으면 scope를 임시 표시 |
| sourceType | (없음) | RegulationHit에 아직 없음. RAG가 source_type 필드를 추가할 예정(5번 계획 1) |
| sourceUrl | source_url | legalize-kr 커밋 링크 또는 공식 페이지 |

결과 수준 필드 제안: standardsSnapshot에는 hits의 (source, version, effective_from) 중복 제거 목록을,
retrievalStatus에는 "connected"(한 번 이상 FOUND 또는 NO_RELEVANT_EVIDENCE), "error"(그 밖) 중 하나를 넣는다.
retrievalStatus 값 이름은 Agent와 Frontend 담당이 확정한다.

#### Frontend

고정 문구 7곳을 result.retrievalStatus 기준으로 바꾼다. "connected"면 근거 표시, 그 밖이면 지금 문구를 유지한다.
basis 렌더링(BasisItem.vue, ReportDocument.vue, ZoomDetailCard.vue)은 위 매핑을 쓰면 그대로 동작한다.

#### Backend (feat/violation-storage, main에 PR #4로 머지됨)

V4 app.violations에는 근거 컬럼이 없다. 근거는 지금처럼 reviews.document JSON의 findings[].basis에 보존된다.
근거를 정규화하려면 violation_basis 같은 별도 테이블이 필요하며 Backend 담당과 합의한다.

### 4. 머지 순서와 알려진 충돌

2026-10-20 git merge-tree로 확인한 결과다.

| 조합 | 결과 | 해결 |
| --- | --- | --- |
| 최신 main(PR #4 포함) + feat/rag-legal-sources | scripts/export_contracts.py 한 파일 충돌 | 양쪽 import와 목록 항목을 모두 남긴다(아래) |
| main + feat/agent | docs/01-architecture.md, services/agent/pyproject.toml, uv.lock 충돌 | feat/agent를 최신 main 기준으로 rebase. RAG 변경과는 무관 |
| feat/rag-legal-sources + feat/agent | 위와 같은 세 파일 | main 충돌을 풀면 함께 해결됨 |

export_contracts.py 충돌 해결 결과:

```python
from capstone_shared.contracts import SearchRulesInput, SearchRulesResult
from capstone_shared.rag_contracts import PrecedentSearchResult, RegulationSearchResult
from capstone_shared.review_contracts import RunRequest, ResumeRequest, RewriteRequest, RewriteSuggestion, TextAssessment, VisualAssessment
...
    ("search-rules.output.schema.json", SearchRulesResult),
    ("search-regulations.output.schema.json", RegulationSearchResult),
    ("search-precedents.output.schema.json", PrecedentSearchResult),
    ("mvp-agent-run.input.schema.json", RunRequest),
    ("mvp-agent-resume.input.schema.json", ResumeRequest),
    ("mvp-agent-rewrite.input.schema.json", RewriteRequest),
    ("mvp-agent-rewrite.output.schema.json", RewriteSuggestion),
```

권장 순서:

1. feat/rag-legal-sources에 최신 main을 merge하고 위 충돌을 해결한다. pytest와 export_contracts.py --check 통과 후 PR. (2026-10-20 완료, PR #5)
2. RAG PR이 머지되면 feat/agent를 최신 main 기준으로 rebase하고 search_regulations로 교체한다.
3. Frontend가 retrievalStatus 기준 표시로 바꾼다.
4. 이후 RAG 품질 작업(5번)은 계약을 바꾸지 않으므로 다른 브랜치와 독립적으로 진행한다.

### 5. 앞으로 RAG 실행 계획

| 순서 | 작업 | 산출물 | 다른 브랜치 영향 |
| --- | --- | --- | --- |
| 1 | RegulationHit에 source_type 추가 | 계약과 스키마 갱신 | Agent와 같은 PR에서 합의 (필드 추가만) |
| 2 | S06 별표 5 PDF 확보와 적재, 이어서 S01, S02, S03, S07, S08, S09 | data/sources/S0x.pdf, knowledge 행 | 없음 (데이터만) |
| 3 | S04 규정과 사례 page_ranges 확정 | search_precedents 결과 생성 | 없음 |
| 4 | 임베딩 모델 확정(text-embedding-3-small, 1024차원)과 적재, 완료 2026-10-21 | retrieval_mode=hybrid | keyword_score, vector_score 필드 추가 |
| 5 | 평가셋: 검증표 예시 4개와 실제 화면 문구("지친 피부 시간을 되돌리는", "탄력 광채 안티에이징") | 기대 조문 정답표와 결과표 | 없음 |
| 6 | 부칙의 조문별 시행일 처리 | 버전 범위 보정 | 없음 |

현재 키워드 검색만으로는 "탄력 광채 안티에이징"이 0건, "지친 피부 시간을 되돌리는"이 제2조(정의)로 잡힌다.
이런 표현의 근거는 별표 5와 지침, 협회 기준에 있으므로 2번과 4번이 끝나야 근거 품질이 의미 있게 오른다.

### 6. 직접 확인하는 방법

```sh
docker compose --profile web up --build -d --wait
GITHUB_TOKEN=$(gh auth token) uv run --all-packages --frozen python scripts/ingest_rag.py legalize
```

```python
# uv run --all-packages --frozen python 로 실행. backend 폴더 기준
import asyncio, os, sys
sys.path.insert(0, "scripts")
from local_config import load_env; load_env()
from fastmcp import Client
from fastmcp.client.auth import BearerAuth

async def main():
    async with Client("http://127.0.0.1:8003/mcp", auth=BearerAuth(os.environ["INTERNAL_SERVICE_TOKEN"])) as c:
        r = (await c.call_tool("search_regulations", {"query_text": "표시 광고 실증 자료", "top_k": 3})).structured_content
        print(r["status"], [(h["source"], h["section"]) for h in r["hits"]])
asyncio.run(main())
```

## 맡을 입출력

| 도구 | 입력 | 출력 필드 | 검색 범위 |
| --- | --- | --- | --- |
| search_regulations | query_text, top_k=5 | source, version, scope, excerpt, similarity, trust_level=high | 컬렉션 A, 법령과 기준 |
| search_precedents | query_text, top_k=3 | source, excerpt, similarity, trust_level=low, verified=False | 컬렉션 B, 보조 사례 |

query_text는 Agent가 추출한 광고 주장과 필요한 문맥이다. RAG에 광고 PDF와 이미지 OCR을 맡기지 않는다.
excerpt는 저장된 원문 그대로 반환한다. similarity는 법 위반 확률이 아니다.
사례는 출처 미검증과 보조 참고 표시를 유지하고 법령 검색 결과에 섞지 않는다.
문서 전체를 보내거나 결론을 만드는 것이 아니라 관련 근거를 찾고 출처를 반환하는 역할이다.

## 지금 있는 개발 기반

- services/rag-mcp의 FastMCP 서버, 개발용 인증과 상태 확인.
- 실행 계약 v0.1의 search_rules, search_cases와 get_evidence. 세 도구 모두 업무 기능은 NOT_IMPLEMENTED다.
- PostgreSQL knowledge 스키마와 rag_reader 및 rag_ingest 권한 분리.
- 문서 버전과 청크, vector(1024) 빈 저장 공간.
- Pydantic 계약, JSON Schema와 테스트, 전체 서비스 연결 smoke.

현재 이름과 반환 타입은 노션 목표와 다르다. 도구의 이름만 바꾸거나 서로 같은 API라고 표시하지 않는다.
get_evidence는 기반의 확장 제안이며 노션의 필수 도구 2개가 아니다.

## 먼저 진행할 계약 PR

Agent 담당자와 새 두 도구의 입력 및 출력 모델을 packages/shared에 작성하고 contracts를 생성한다.
현재 consumers와 테스트의 호출도 같은 PR 또는 연결된 PR에서 전환한다.
노션에 없는 필드를 모두 추가할 때까지 검색 개발을 멈출 필요는 없다. 다음 추적 및 오류 정책만 명시한다.

| 결정 | 필요한 이유 |
| --- | --- |
| 기존 quote와 목표 excerpt, document_version과 version 매핑 | 같은 원문과 버전이 반환돼야 함 |
| document ID, source URL, page와 section 보존 | 리포트에서 원문 위치를 다시 확인할 수 있어야 함 |
| review_date와 시행일, corpus_version | 적용할 버전과 검색 재현성 보장 |
| 빈 결과와 DB 장애 및 미구현의 구분 | 빈 목록을 적합 판정으로 오해하지 않도록 함 |
| 기존 v0.1의 제거 또는 호환 기간 | Agent가 두 종류 도구를 같은 것으로 중복 호출하지 않도록 함 |

claim_id와 위치 연결은 Agent 문맥에서 유지할 수 있다. 새 도구가 필수 인자로 받는다고 임의로 추가하지 않는다.
노션의 출력 목록에 오류를 어떻게 전달할지 정하고, 장애를 정상적인 빈 목록으로 변환하지 않는다.

## 구현 순서

1. 공개 출처 목록, 발행기관과 개정 버전 및 적용 범위를 정리한다.
2. 법령과 기준은 rules, 확보한 사례는 cases로 분리한다.
3. PDF 본문과 표, 비고 및 예외 조건을 파싱하고 문서 버전과 페이지를 보존한다.
4. rag_ingest로 원문과 메타데이터를 적재한다. 기존 버전을 덮어쓰지 않는다.
5. 선택한 임베딩 모델로 청크를 벡터화한다. text-embedding-3-small을 1024차원으로 받는다. bge-m3는 비교했지만 이미지와 CPU 부담 때문에 기본값에서 뺐다.
6. rag_reader로 키워드와 벡터 검색을 구현하고 필터 및 순위 조정을 평가한다.
7. 내부 검색 결과를 위 두 노션 반환 타입으로 변환한다. 인용과 자료 역할을 검증한다.
8. Agent가 실제 MCP tools/call로 두 도구를 선택해 호출하도록 통합한다.

공개 문서 수집과 색인은 별도 배치다. 광고 검토 요청마다 전체 문서를 다시 색인하지 않는다.
고객 광고와 비공개 증빙은 공개 코퍼스에 넣지 않는다. 사내 지침은 기업 권한과 삭제 전파를 설계한 뒤 확장한다.
선택 의존성은 uv sync --package capstone-rag-mcp --extra ingest로 설치한다. 이 명령이 실제 수집을 실행하지는 않는다.

## 다른 담당자와 나눌 일

- Agent: 광고 파싱과 주장 구조화, 검색 질의 선택, 여러 도구 결과를 종합한 판단과 리라이트 및 HITL.
- Business MCP: 증빙 구조화, 제품 범위 대조, 식약처와 KIPRIS 조회 및 특허 관련성 대조.
- Backend: 제품과 파일 권한, 검토 이력과 버전, 결과 보존 및 사용자 API.
- RAG: 공개 문서 수집, 법령 및 사례 검색, 원문과 출처 및 버전, 검색 평가.

공개 규정이 스캔본이면 RAG 수집 배치가 OCR을 사용할 수 있다. 광고 OCR을 RAG가 담당한다는 뜻은 아니다.
특허 청구항 수집과 조회를 RAG에 중복 배정하지 않는다.

## 완료 증거

- 기대한 법령과 조건이 검색 결과에 포함되는지 확인한 평가표.
- excerpt가 저장 원문에 실제 포함되는지 확인한 결과.
- 예외 조건, 개정 전후 문서와 적용 범위를 혼동하지 않는 결과.
- 사례의 trust_level과 verified=False 및 보조 표시.
- 검색 결과 없음과 DB 장애 시 서로 다른 처리.
- Agent의 실제 두 MCP 도구 호출과 소비자 계약 검증.

## 출처 시작점

- 식약처 관리 지침: https://www.mfds.go.kr/brd/m_1060/view.do?seq=15700
- 협회 광고자문 해설서: https://kcia.or.kr/ad/cs/notice.php?no=17039&type=view
- 실증 시험방법 안내: https://www.mfds.go.kr/law/board/boardDetail.do?brdId=data0011&menuKey=38&seq=15567
- 2026 정책설명회와 사례: https://www.mfds.go.kr/brd/m_641/view.do?seq=32476
- 임베딩 후보: https://huggingface.co/BAAI/bge-m3

위 링크의 실제 개정 여부와 파일 내용은 수집 담당자가 다시 확인한다. 아직 DB에 수집한 자료는 없다.


## 로컬 Handoff 반영 계획

`Cosmetic_RAG_AI_Handoff`의 9개 출처와 검증표를 다음 RAG 작업의 기준으로 사용한다.
원문은 Git에 넣지 않으며 로컬 수집과 기본 청킹 점검을 제공한다. 임베딩과 DB 적재 및 검색은 구현하지 않았다.
DB는 기존 로컬 Docker PostgreSQL의 knowledge 스키마와 vector(1024)를 사용한다.
실행 준비와 권한 확인은 docs/02-development.md, 수집 순서와 자료별 경계는 docs/04-data.md를 따른다.
법제처 API는 수집 배치에 사용하고, 특허 및 제품 조회는 Business MCP의 실시간 경로다.

## main을 clone한 뒤 바로 확인할 파일

Desktop Handoff 폴더가 팀원 컴퓨터에 없어도 개발을 시작할 수 있도록 기준을 저장소에 반영했다.

- 공개 근거 9건: services/api/src/main/resources/reference-sources.json.
- 노션 사용 API 4종: services/api/src/main/resources/external-api-catalog.json.
- 공식 포털에서 확인한 식약처의 실제 파라미터와 응답 컬럼: contracts/external/*.swagger.json.
- 수집과 기본 청킹 점검: scripts/audit_sources.py와 services/rag-mcp/src/capstone_rag_mcp/preview.py.
- 실제 원문과 청크 미리보기: 실행자가 로컬에서 생성하는 data/source-audit, Git 제외.

```sh
uv run --all-packages --frozen python scripts/audit_sources.py --download --probe-without-key
```

이 명령은 출처 9건과 API 4종을 따로 기록한다. 공식 페이지 HTTP 200, PDF 다운로드와 제목 대조,
기본 문자 분할, 승인키를 사용한 정상 API 응답은 서로 다른 검증 단계다.
LAW_OC가 있으면 화장품법 제13조와 제14조, 표시광고법 제3조 및 실증 규정의 XML 수집도 시도한다.
키 값을 출력하거나 요청 URL의 키를 보고서에 넣지 않는다.

기본 청킹은 페이지별 최대 900자와 150자 겹침이며 원문 start/end와 페이지를 보존한다.
이는 초기 입출력 검증용이다. 표와 예외의 의미 단위 복원, 혼합 문서 S04의 규정 및 사례 분리,
시행일 확인과 코퍼스 버전 확정, 임베딩 및 DB 적재와 검색 평가는 다음 RAG 작업이다.
공식 PDF를 받았다고 source_verified=true 또는 retrieval_ready=true로 바꾸지 않는다.
