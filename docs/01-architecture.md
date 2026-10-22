# 서비스 아키텍처

## 현재 코드 소유 경계

frontend는 화면, backend는 Java 업무 API와 통합 인프라, ai_agent는 Python Agent와 Business MCP 및 RAG MCP를 소유한다.
저장소만 분리하며 실행 서비스, HTTP와 MCP 계약, DB 권한과 Docker 네트워크는 유지한다.
Python 공통 모델과 생성 JSON Schema는 ai_agent/packages/shared와 ai_agent/contracts가 원본이다.
통합 Compose는 backend에서 ai_agent의 빌드 컨텍스트를 참조한다. 모델과 프롬프트, 토큰 예산과 검색 설정은 변경하지 않는다.


기준은 [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)와 연결된 MCP, REST, Graph 및 RabbitMQ 문서다.
이번 구현은 제공된 Vue 화면을 연결한 단건 MVP다. RAG DB 계획은 `Cosmetic_RAG_AI_Handoff`를 반영한다.
이 문서는 현재 동작과 최종 목표를 구분한다. 팀원의 통합 기준은 두 저장소의 main이며 기능별 feat 브랜치와 PR로 작업한다.

## 현재 실행 구조

### Java API 코드 배치 (2026-10-22 구조 정리 B, C)

`org.skala.capstone` 아래에서 `common`은 인증 설정과 공통 오류 및 Agent HTTP 클라이언트를,
`auth`는 사용자 인증과 세션을, `billing`은 결제와 크레딧 원장을 소유한다.
`reference`는 공개 근거 조회, `system`은 내부 환경 확인 API다.
인증과 결제 요청은 `dto`의 record로 받고 Controller는 Service에 위임한다.
SQL은 UserRepository, SessionRepository, CreditRepository, SystemRepository에서 실행한다.
JDBC와 기존 DB 스키마를 유지하며 회원가입, 팀원 추가, Refresh 회전, 결제 확정의
트랜잭션은 Service로 이동했다. Refresh 재사용 시 세션 폐기를 커밋하는 규칙도 유지한다.

`review`의 ReviewController는 사용자 조회와 접수 및 삭제, InternalReviewController는
Agent의 실행 범위가 제한된 파일 조회와 진행 이벤트를 받는다. ReviewService는 접수와
삭제, ReviewRunner는 Agent 실행과 성공 및 실패 정산, ReviewAssetService는 파일 검증과
영구 원본 및 Redis 캐시를 담당한다. ReviewRepository, AssetRepository, RunTokenRepository가
리뷰 문서, 원본, 실행 토큰의 SQL을 소유하며 JdbcTemplate을 외부로 노출하지 않는다.
`review.events`는 SSE, `review.queue`는 큐 선언과 발행 및 소비를 담당하고 JobRepository가
outbox SQL을 소유한다.

`violation`의 ResultWriter는 Agent 결과와 위반 문구 저장, ViolationDecisionService는
문구별 결정과 다시쓰기, ReviewViewAssembler는 목록과 상세 응답 조립을 맡는다.
ViolationRepository는 위반 문구 및 수정안 SQL과 조회용 record를 제공한다.

접수, 크레딧 예약, 실행 토큰과 outbox 저장은 같은 트랜잭션이다. 큐를 끄면 커밋 후에만
기존 크기의 실행 풀에 제출한다. 결과 저장과 과금 정산은 같은 TransactionTemplate에서
실행하고 실패 환불은 REQUIRES_NEW를 유지한다. 문구 결정의 review 및 violation FOR UPDATE,
Agent 호출을 잠금 밖에서 수행하는 순서, 큐의 publisher confirm과 수동 ACK,
5초 및 30초 재시도, 최대 3회 시도와 7분 정체 작업 복구도 유지한다.
이번 구조 정리에서는 URL과 JSON 응답, DB 스키마 및 설정을 변경하지 않았다.

인증 동작 변경은 구조 이동과 별도 커밋이다. 회원가입과 팀원 추가는 비밀번호의 앞뒤 공백을
보존하고 로그인은 입력 원문을 비교한다. 기존 최소 길이 검사는 유지하며 원문도 최대 72자로
제한한다. 기존 계정 해시는 변경하지 않는다. 팀원 추가도 이메일 형식을 검사하고
`INVALID_EMAIL` 또는 중복 시 `EMAIL_EXISTS`를 반환한다.
record DTO에서 누락되거나 null인 필수 문자열은 입력 오류로 처리한다.

### 리뷰 상태와 장애 복구 보강 (2026-10-22)

`review.model`의 ReviewDocument와 ReviewVersion은 JSONB의 예전 필드와 버전을 보존하면서
상태 전이를 담당한다. ReviewStatus는 queued, analyzing, done, failed를 구분하고,
AnalysisResult가 문구와 사진 모두 complete인 결과의 과금 가능 여부를 판단한다.
완료되거나 실패한 버전은 다시 분석 중으로 바뀌지 않으며 실패한 버전에 늦게 도착한 결과도 저장하지 않는다.
Agent 결과의 확장 필드는 JSON으로 유지하고 JPA나 DB 스키마 정규화로 전환하지 않는다.

ReviewSubmission은 파일 수, 크기와 서명 검증 및 파일 읽기를 DB 트랜잭션 전에 끝낸 입력이다.
ReviewService는 이후 TransactionTemplate 안에서 원본, 검토, 실행 토큰, 예약과 outbox를 함께 저장한다.
업로드 응답은 Redis 쓰기를 기다리지 않는다. 원본은 커밋 후 첫 조회에서 캐시에 넣고,
원본 조회는 실행 범위를 검사한 뒤 캐시 장애 시 DB로 대체한다. 캐시 오류 후 30초 동안은
Redis를 우회해 파일마다 같은 연결 지연을 반복하지 않는다. 캐시 정리가 안 되면 기존 TTL로 정리한다.
진행 및 완료 이벤트는 외부 트랜잭션에 참여한 경우에도 커밋 이후에만 발행한다.

실패 의도는 V16의 review_failure_requests에 먼저 보존한다. ReviewFailureService는 실패 상태와
예약 반환을 한 트랜잭션으로 정산하고 커밋 뒤 실패 이벤트를 발행한다. 실패 요청과 큐가 없는
정체 예약은 60초마다 복구한다. DB 장애 중 반환 지연은 가능하며 복구를 통한 최종 반환을 목표로 한다.

전체 점검에서 SSE 재생과 구독 등록 사이의 완료 이벤트 누락을 방지하고, 재생된 완료 및 실패
이벤트에서도 스트림을 닫도록 보강했다. 새 비밀번호는 BCrypt의 UTF-8 72바이트 한도를 입력 단계에서
검사한다. 기존 계정 해시와 로그인 비교 방식은 변경하지 않는다.

```mermaid
flowchart TB
  U[기업 작성자와 검토자] --> V[Vue 3, Vite, Pinia]
  V -->|JWT, /api/v1, multipart와 상태 폴링| B[Spring Boot 3, Java 21]
  B -->|파일 임시 저장| R[Redis: 30분 안전 TTL]
  B -->|회원, 기업, 결과, 버전, 승인| APP[(로컬 PostgreSQL: app)]
  B -->|내부 인증 HTTP: 단건 실행| A[FastAPI, LangGraph]
  A -->|범위가 제한된 asset 요청| B
  A --> P[pdfplumber, pypdfium2, Pillow]
  P --> T[문구: native PDF + Tesseract 한국어 OCR]
  T --> TL[OpenAI: 문구 예비검토, Structured Output]
  P --> VL[사진: OpenAI VLM 독립 검토]
  TL --> M[결과 합류]
  VL --> M
  M -->|인증된 MCP tools/call| BM[Business MCP: FastMCP]
  BM -->|증빙 asset 읽기| B
  BM -->|서버의 승인키로 조회| EX[식약처, KIPRIS Plus]
  BM --> H[LangGraph 결과와 checkpoint]
  H --> CP[(같은 PostgreSQL: agent checkpoint)]
  H -->|검토 결과, 곧 리포트| B
  V -->|문구별 결정과 다시쓰기| B
  H -->|리포트 데이터| B
  A -->|문구마다 search_regulations| RM[RAG MCP]
  RM -. 읽기 전용 .-> K[(같은 PostgreSQL: knowledge, pgvector)]
  LK[법제처 API 화장품법, 시행령, 시행규칙, 고시] -->|scripts/ingest_rag.py lawapi| K
  PDF[나머지 출처 PDF] -. scripts/ingest_rag.py pdf, 원문 확보 후 .-> K
```

pgvector는 PostgreSQL의 확장이다. 별도 벡터 서버나 클라우드 계정을 만들지 않는다.
하나의 로컬 DB 서버 안에서 app, agent, knowledge 스키마와 DB 사용자를 분리한다.
과금 원장은 목표상 별도 서비스와 DB이며 현재 실행 구조에 넣지 않았다.

## 서비스 분할과 기술

| 서비스 | 현재 책임과 코드 | 주요 기술 | 다음 작업 |
| --- | --- | --- | --- |
| Frontend | 로그인, PDF와 이미지 선택, 진행, 원문과 사진 결과, 승인, 이력, 출처 목록 | Vue 3, Vite, Pinia, Router | 정밀 위치 UX, API 항목 재조회, 팀원 관리 UI |
| Backend | 기업 권한, JWT 세션, 업로드, 결과와 광고 버전, 결정 저장, 원본 폐기 | Spring Boot 3, Security, JDBC, Flyway, Redis | SSE, 내구성 있는 작업 발행, 도메인 테이블 정규화 |
| Agent | 광고 파싱, 두 분석 경로, 조건부 Business MCP 호출, HITL | FastAPI, LangGraph, OpenAI Responses, Pydantic, PostgreSQL checkpointer | 근거 검색과 검증 루프, API 사용량 계측, 제한 재시도 확장 |
| Business MCP | 증빙 문자 추출, 식약처 품목 조회 adapter, KIPRIS 등록 후보 조회, GMP 업체와 성분 규제정보 조회 | FastMCP, httpx, 공통 파서 | 증빙 의미 구조화와 제품 주장 대조, 청구항 원문 확보와 대조 |
| RAG MCP | search_regulations, search_precedents(키워드 검색, 임베딩 선택), 기존 v0.1 stub 유지. 화장품법과 고시는 법제처 API, 나머지는 PDF 배치 적재 | FastMCP, psycopg, pgvector | PDF 원문 확보와 적재, 임베딩 모델 확정, 검색 평가, Agent 호출 연결 |
| Data | app, agent와 빈 knowledge, 원본 임시 캐시 | PostgreSQL 17, pgvector, Redis | 공개 코퍼스 적재와 검색 평가 |

Agent API와 Worker는 노션의 한 Agent 서비스 안의 역할이다. 입력 처리를 위한 세 번째 MCP나 새 Worker 배포 서비스를 추가하지 않는다.
업무 DB는 Backend가 소유한다. Business MCP는 Backend 내부 API를 사용하고 DB나 Redis에 직접 연결하지 않는다.
Agent의 DB 직접 접근은 agent checkpointer만 허용한다.

## 입력부터 결과까지

1. 기업 작업공간을 생성하거나 로그인한다. 최초 계정은 해당 기업 관리자이며 다른 기업 자료 접근은 서버에서 차단한다.
2. 제품정보와 광고 파일을 제출한다. 광고 입력은 PDF와 이미지 두 종류이고 증빙 파일은 별도 역할로 선택 제출한다.
3. Backend가 실제 파일 서명과 크기를 확인하고 asset 메타데이터 및 SHA-256, 버전을 app에 저장한다. 원본 바이트는 Redis에 임시 보관한다.
4. 접수 트랜잭션을 저장한 뒤 202와 review 객체를 반환한다. 제한된 API 실행 풀이 Agent를 호출하며 Vue는 상태를 폴링한다.
5. Agent가 실행별 토큰으로 해당 버전의 광고만 읽는다. PDF native 문자와 페이지 렌더, 긴 이미지 타일을 준비한다.
6. 문구는 PDF 파싱과 OCR 후 LLM으로 확인할 주장 후보를 만든다. 사진은 별도 VLM 호출로 비교 표현과 조명 및 각도 등 관찰 한계를 기록한다.
7. 두 결과가 합류한다. business 노드는 findings와 증빙·제품·특허번호 정황만 LLM에 보여주고, structure_evidence/verify_functional_approval/verify_patent 중 필요한 도구만 LLM이 골라 호출한다(2026-10-21부터 입력값 존재 여부의 조건문이 아니라 OpenAI tool-calling 기반 도구선택 loop, Guardrail: 총 8회/동일 tool 연속 2회/60초). 도구 오류는 항목별로 분리한다.
8. Graph는 초안과 사람 확인 대기를 PostgreSQL checkpoint에 저장하고 실행 요청을 종료한다. Backend가 초안을 app에 보존하고 원본을 Redis에서 삭제한다.
9. 결과는 바로 리포트로 보인다. 작성자, 검토자, 관리자는 위반 문구마다 원문 유지, 수정안 채택, 직접 수정(Agent 재검증), 다시쓰기(Agent 생성)를 그때그때 한다.
   검토 전체 승인, 검토 완료 상태, 재검토는 2026-10-22에 없앴다. 수정한 광고는 새 검토로 올린다.
10. 결과와 이력을 다시 조회하고 브라우저 인쇄로 PDF 저장 또는 HTML 내보내기를 한다. 서버가 PDF 바이너리를 생성하는 기능은 아직 없다.

처리 완료는 AI 추론이나 법령 적합성 확인 완료와 다르다. 모델 키가 없으면 native PDF와 로컬 OCR은 수행해도 문구 모델과 사진 모델은 `not_configured`다.
검토 그래프는 목적별 단계로 나뉜다 (2026-10-21, feat/review-evidence-pipeline).

| 노드 | 목적 | 부르는 도구 |
| --- | --- | --- |
| text_path, photo_path | OCR(Tesseract + VLM 보충, 음절 분리 교정)과 주장 추출, 사진 관찰 | OpenAI |
| regulations (legal_basis) | 규칙 스캔으로 모델이 놓친 고위험 줄을 보충하고, 문구마다 규칙 조항, 보조 검색, 사례를 붙인다 | rag.scan_claim_lines, rag.lookup_claim_rules, rag.search_regulations, rag.search_precedents |
| business | LLM이 findings를 보고 세 도구 중 필요한 것만 선택(2026-10-21부터 도구선택 loop) | business.structure_evidence, verify_functional_approval, verify_patent |
| cross_checks | 규칙이 지정한 대조만 실행: 기능성 보고 vs 주장, 실증자료 제출, 성분 국내 금지와 제한, GMP, 특허 번호 | business.find_functional_reports, verify_gmp_company, rag.check_ingredient_standard |
| violations | 조문을 참고한 자가검증 리라이트. 통과한 문안이 finding.rewrites로 저장된다 | OpenAI |
| approval, report | ai_agent에 남아 있지만 Backend가 재개를 보내지 않는다(2026-10-22 승인 제거). report는 하는 일이 없다 | |

결과의 retrievalStatus는 connected, not_indexed, error, skipped 중 하나다. 근거와 대조 결과는 확인할 후보일 뿐이라 `legalStatus=not_evaluated`를 유지하고 적합 점수를 만들지 않는다.
finding에는 basis(규칙 조항 retrieval=rule, 보조 검색 retrieval=search, 연결 조항 related), rules, evidenceRequirements, checks가 붙는다.
사진은 실제 효능, 보정 여부와 동일 인물을 확정하지 않는다. 사진 결과를 억지로 텍스트 RAG 결과로 바꾸지 않는다.

## 수명과 실패 처리

- 원본은 처리 종료 시 삭제하고 30분 TTL은 비정상 종료의 안전장치로 둔다. 결과와 파생 텍스트는 별도 정책으로 DB에 보관한다.
- 원본 이미지와 요청 토큰은 closure와 내부 요청에만 두고 Graph checkpoint나 공개 결과에 넣지 않는다.
- 같은 브라우저의 선택한 File은 임시 미리보기가 가능하지만 새로고침하거나 다른 기기에서 열면 원본이 없다. 같은 파일 재열람 기능은 후속이다.
- 파일이 손상됐거나 지원하지 않는 형식이면 접수 또는 파싱 단계에서 실패한다. 실패한 작업은 새 원본을 제출한다.
- 모델과 공공 API 오류는 적합 판정으로 바꾸지 않는다. 정상 조회 결과 없음과 API 설정 또는 연결 실패를 구분한다.
- 승인 결정과 결정 후 Agent 재개 작업(app.review_jobs kind=resume)은 2026-10-22에 없앴다. 남은 큐 작업은 분석(run)뿐이다.
- 삭제는 실행 중인 작업을 막고 체크포인트부터 삭제한 뒤 app 이력과 asset 메타데이터를 삭제한다.

## 노션 목표와 현재 MVP의 차이

| 목표 | 현재 |
| --- | --- |
| 단건 즉시 응답과 SSE | 접수 후 202, 인증된 검토별 SSE(GET /api/v1/reviews/{id}/events). 스트림을 열지 못하면 1.5초 상태 폴링 |
| 다건 Backend → Agent API → RabbitMQ → 내부 Worker | 단건 검사의 Backend → RabbitMQ → Backend 소비자 → Agent HTTP 경로부터 연결. 다건 Agent 내부 Worker는 후속 |
| 한도 있는 Agent 도구선택 loop | business 노드는 구현됨(2026-10-21): LLM이 findings를 보고 structure_evidence/verify_functional_approval/verify_patent 중 필요한 것만 선택, Guardrail 총 8회/동일 tool 연속 2회/60초. regulations/cross_checks는 여전히 고정 순서(법령 근거는 매 finding마다 항상 필요하고, cross_checks 대상은 규칙 테이블이 정함) |
| search_regulations, search_precedents | 화장품법 코퍼스(법제처 API)는 적재·임베딩과 pgvector 검색이 연결됨. 나머지 출처(PDF) 적재는 원문 확보 후 진행 중 |
| 증빙 및 제품 주장 대조와 특허 청구항 대조 | 증빙 문자 추출과 조회 adapter까지만 구현 |
| 사용량 이벤트와 별도 과금 원장 | app 스키마 내 Toss 테스트결제·크레딧 구매·리뷰당 크레딧 차감이 실제 동작. ERD v2의 judged_at 기준 reserve/charge/release와 별도 과금 DB 분리는 미구현 |
| 서버 리포트 PDF | 브라우저 인쇄와 HTML 내보내기 |
| violation 단위 HITL(위반 줄마다 원문유지/자동리라이트채택/직접수정) + review 전체 승인, 반려, 원본재업로드요청 결정 분리(2026-10-20 HITL 재설계) | 구현됨. `app.violations`/`app.violation_rewrites`가 원본이며, decision/rewrite-requests REST API와 rewritten_manual 재검증(`/internal/rewrites/verify`)까지 Backend·Agent에 연결됐고 프론트(ViolationCard.vue/RevisionOptions.vue)도 해당 API를 호출함 |

Compose의 단건 검사는 `app.review_jobs`와 RabbitMQ quorum 큐를 사용한다. DB 작업 기록을 먼저
커밋하고 발행 확인 후 상태를 바꾼다. 소비자는 수동 ACK를 사용하며 일시적 실패는
5초, 30초 간격으로 최대 3회 시도한 뒤 실패 큐에 넣는다. 재시도와 실패에는 마지막 오류와
실패한 실행의 LangSmith run id(`last_trace_id`, 추적이 켜진 경우)를 남긴다. Agent 호출은 현재
Backend 소비자가 맡는다. 단독 API 실행에서 `REVIEW_QUEUE_ENABLED=false`면
기존 프로세스 내부 실행 풀을 사용하므로 재시작 복구가 되지 않는다.
다건의 목표는 접수 기록 저장 후 202, 승인 대기 checkpoint 저장 후 ACK, 승인 결정 저장 후 별도 재개 메시지다.
업무 메시지에는 원본 바이트를 넣지 않으며 publisher confirm, 수동 ACK, 멱등키와 DLQ를 함께 구현해야 한다.

## RAG 담당의 다음 연결

공개 PDF와 법제처 API 원문을 검증하고 knowledge에 미리 색인한다. 제품 및 특허 조회 API는 Business MCP 실시간 경로로 남긴다.
S01~S09와 미확인 개정일은 출처 후보이며 이번에 수집한 법률 자료가 아니다.
자세한 표와 단계는 [데이터 계획](04-data.md), [RAG 시작점](06-rag-start.md), 실제 입출력은 [공통 계약](03-contracts.md)에 있다.


## 노션 ERD v2 연결

[최신 ERD](https://app.notion.com/p/Data-ERD-App-State-Vector-DB-3ff616ab8fec81c7b57ac75358ced570)는 주요 DB 한 개의 app/state/vector 분리와 별도 과금 DB를 명시한다.
현재 물리 스키마 app/agent/knowledge와 목표 테이블의 대응은 [데이터 모델](04-data.md)에 있다.
RAG의 knowledge는 Handoff 기준을 유지하고 Agent의 광고 파싱과 Business MCP의 증빙 처리를 섞지 않는다.
키가 없으면 모델과 공식 API를 실행한 것으로 표시하지 않는다. 원문 수집 및 기본 청킹 점검은 검색 색인과 분리한다.
