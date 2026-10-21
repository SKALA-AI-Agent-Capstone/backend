# 프로젝트 메모리

마지막 노션 재점검: 2026-10-20.
원본은 [기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)와 연결된 상세 문서다.
이 파일은 저장소의 공유 맥락이며 계정 전체의 별도 메모리가 아니다.
Notion 연동 API가 해당 페이지를 반환하지 않아 브라우저에서 본문과 상세 문서, 시퀀스 원본 이미지를 읽었다.
노션 원문과 API 등록부 자체는 수정하지 않았다.

## 유지할 사용자 의도

- B2B 화장품 광고 사전검토이며 사용자는 RAG MCP 담당이다.
- 초기 환경 범위는 최신 사용자 요청으로 단건 MVP 구현까지 확장됐다. 2026-10-20에 RAG 검색(키워드 우선, 임베딩 선택)을 feat/rag-legal-sources에서 추가했다.
- 사용자는 프로젝트 방법을 배우며 직접 개발한다. 역할별 작은 계약과 구현, 검증과 동료 리뷰를 따른다.
- PDF와 이미지 두 종류만 광고 입력으로 제공한다. 직접 카피 UI는 제외한다.
- 제공된 Vue 프론트에 실제 파일 선택과 시작, 서버 API 연결을 반영한다. 프론트에서 가짜 판정을 만들지 않는다.
- RabbitMQ만 중심으로 설계하지 않는다. Frontend, Backend, Agent, 두 MCP와 데이터 및 과금 경계를 함께 본다.
- 기존 파일에 직접 수정하며 중앙점을 사용하지 않는다. 별도 코드 사본과 제출본을 임의로 만들지 않는다.

## 2026-10-20 RAG 수집 경로 결정

- 사용자 결정: 화장품법은 legalize-kr에서 가져와 벡터 DB에 넣고, 나머지 참고자료는 PDF로 넣는다.
- 사용 API 4종(보고품목, KIPRIS, GMP, 규제정보)은 색인하지 않고 MCP 도구로 두어 Agent가 골라 쓴다. 키가 없으면 API_NOT_CONFIGURED다.
- 작업 브랜치는 feat/rag-legal-sources이며 RAG와 MCP 영역만 바꾸고 Agent, Backend, Frontend의 동작은 그대로 둔다.
- 화면의 "법령 검색 미연결"은 Agent가 rag-mcp를 호출하지 않고 Frontend 문구가 고정이기 때문이다. RAG 데이터와 도구는 동작한다.
- 다른 브랜치가 맞출 계약, 화면 basis 매핑, 머지 순서와 충돌 해결은 docs/06-rag-start.md의 통합 가이드에 둔다.

## 이번에 실제로 읽은 문서

| 문서 | 확인 범위 |
| --- | --- |
| 기획서 | FR, NFR, 기술 선택과 저장 정책, 다음 단계, 연결 문서 목록 |
| MCP 외부 API 반영본과 사본 (1) | 7개 도구의 함수 인자와 반환 필드, KIPRIS 및 식약처 파싱 규칙과 확인 필요 항목 |
| Backend REST 초안 | multipart와 결과, HITL와 이력, 과금 및 오류 예시 |
| AI 광고 검토 API 명세 등록부 | 14개 API 항목의 메서드와 /api/v1 경로, 상태와 비고 |
| Agent Graph 초안 | State, 도구선택 loop, 조건, HITL와 리포트, 단일 Agent 서비스 설명 |
| RabbitMQ 시퀀스 | 이미지 두 개의 접수와 발행, ACK와 승인 및 재개, 버전 분기 |

MCP 두 문서에서 확인한 7개 도구의 이름, 인자와 반환 필드는 같다.
별도 새 페이지 링크는 제목만 보였고 설계 내용이 없었다. 명세 원본과 상세 비교는 docs/03-contracts.md를 따른다.

## 개발 목표로 사용할 경계

- Vue는 Spring Boot의 공개 API만 호출한다. DB와 MCP 및 모델 키를 브라우저에 전달하지 않는다.
- Backend는 인증과 기업 권한, 원본 asset과 버전, 검토 이력 CRUD와 결과 영구 보존을 소유한다.
- Agent는 광고 입력 파싱과 Vision, 주장 추출, 도구선택 loop와 리라이트, HITL와 리포트를 맡는다.
- Agent API, Worker와 Graph는 한 Agent 서비스의 내부 역할이며 별도 Worker 배포 서비스를 임의 추가하지 않는다.
- Business MCP는 structure_evidence, match_product_claims, verify_functional_approval, verify_patent, match_patent_claims를 맡는다.
- RAG MCP는 search_regulations(query_text, top_k=5)와 search_precedents(query_text, top_k=3)를 목표로 한다.
- 공개 문서 색인은 별도 RAG 배치다. 광고와 비공개 증빙을 공개 knowledge에 적재하지 않는다.
- PostgreSQL app, agent와 knowledge를 나누고 Vector DB는 같은 PostgreSQL의 pgvector를 사용한다.
- Agent의 DB 직접 접근은 agent 스키마의 checkpointer만 예외다. Business MCP도 업무 DB와 Redis에 직접 접속하지 않는다.
- 원본은 Redis 임시 캐시, 결과와 승인 이력은 PostgreSQL 영구 저장이다. 원본 폐기 후 항상 재열람할 수 있다고 약속하지 않는다.
- 과금과 크레딧 원장은 별도 Application 서비스와 DB다. LLM과 MCP 판단에 맡기지 않는다.

## 큐와 HITL 목표

- 다건 Backend → Agent API → RabbitMQ → Agent 내부 Worker → 같은 Graph다. 단건 큐 통일은 아직 확정되지 않았다.
- 시퀀스 이미지의 202는 작업 및 발행 대기 기록 저장 뒤, 브로커 발행 전에 반환한다.
- Worker는 초안과 승인 대기 checkpoint 저장 후 그 메시지를 ACK한다. 사람을 기다리며 메시지와 실행 Worker를 계속 점유하지 않는다.
- Backend가 결정과 권한 및 광고 버전을 검증해 저장하고 Agent에 재개를 요청한다. Agent는 재개 작업을 발행하고 Worker가 상태를 복원한다.
- taskId와 commandId, reviewId와 batchId의 관계와 메시지 스키마, 제한 재시도 및 DLQ와 멱등성은 구현 계약에 고정한다.

## 확인된 문서 간 차이와 남은 결정

- API 등록부는 /api/v1과 reviewId, batchId지만 별도 REST 초안은 /api와 snake_case다. 목표 경로는 등록부를 사용하고 JSON 필드까지 이미 통일됐다고 주장하지 않는다.
- REST의 단건 completed 응답만으로 Graph의 HITL pending을 표현할 수 없다. 실행과 승인 상태를 구분한다.
- 식약처 MCP는 품목명 fallback을 지원하지만 Graph는 번호가 있을 때만 호출하도록 적었다. 조건을 맞춰야 한다.
- FR-14는 광고와 증빙에서 특허번호를 찾지만 Graph 호출 조건은 증빙 번호 중심이다. 광고에서 찾은 번호도 연결해야 한다.
- FR-15의 청구항 및 명세서 대조와 현재 getWordSearch 기반 명칭, 초록 및 IPC 대조는 범위가 다르다.
- EvidenceFile 필드와 문서별 confidence 및 오류, SPF와 PA 비교 입력, RAG 목록의 오류 표현은 상세 보완이 필요하다.
- RAG 결과는 출처, 버전, 범위와 원문을 반환하며 사례는 trust_level=low와 verified=False를 사용한다. 기존 인용 ID와 페이지 및 적용일의 보존도 전환 계약에 포함한다.
- Graph의 반려 및 수정요청 재실행과 흐름 이미지의 새 광고 버전 및 자료 보완을 함께 맞춘다. 모든 반려를 자동 유료 재검토로 보내지 않는다.
- 원본 재사용과 폐기 시점, 동시 잔액 확인, 중도 실패 및 HITL 대기의 과금 시점은 남은 정책이다.
- 외부 API 요청 및 응답 매핑 초안은 이미 있다. 증빙 번호 대응과 추정 컬럼은 실제 샘플로 검증한다.

## 폐기하거나 구분할 이전 가정

- Redis Queue/Celery 또는 SQS 큐 후보는 이전 가정이다. 큐는 RabbitMQ로 확정됐다.
- prepare_input은 노션의 필수 도구가 아니다. 광고 파싱을 Business MCP의 신규 필수 도구로 강제하지 않는다.
- search_rules, search_cases와 get_evidence는 현재 v0.1 기반이다. 최신 목표의 도구 이름과 같은 것으로 취급하지 않는다.
- 원본 광고를 일반 파일 영역이나 S3에 영구 보관한다는 제안을 현재 보존 정책으로 사용하지 않는다.
- 특허 검증은 RAG, 이력 삭제는 Business MCP라는 분담은 폐기한다.
- 모든 기술과 필드가 미정이라 착수할 수 없다는 해석은 잘못이다. 명시된 인터페이스를 기준으로 개발하고 불일치 부분만 계약 PR로 해결한다.

## 초기 기반 이력

아래는 단건 MVP 구현 이전의 기록이다. 당시 실행 코드는 개발 기반 v0.1이다. 목표 7개 업무 도구와 /api/v1 업무 API, 실제 업로드와 검색 및 Graph는 미구현이다.
Java 21, Python 3.12, FastMCP 4.1.1, PostgreSQL 17과 pgvector, RabbitMQ 4.3.6을 준비했다.
로컬 bge-m3와 vector(1024)는 임베딩 준비값이며 수집과 모델 실행을 수행한 적은 없다.

2026-10-20 초기 기반 검증에서 Python 12개, Java 2개와 Maven verify, Frontend 빌드,
전체 8개 서비스와 Vue 프록시, HTTP 및 MCP와 pgvector 권한, RabbitMQ 합성 AMQP 검사를 통과했다.
이번 상세 명세 재점검은 Markdown만 변경한다. OCR나 API 기능을 새로 실행한 것으로 보고하지 않는다.
기존 두 저장소의 chore/dev-foundation과 Draft PR #1을 재사용하며 main 병합과 브랜치 보호 변경은 하지 않는다.


## 현재 사용자 요청: 단건 최소 기능 구현

이전의 개발환경만 구성한다는 범위는 이번 요청에 한해 확장됐다.
사용자는 제공한 mfds-ad-review-portal Vue 화면을 연결하고 벡터 검색을 제외한 최소 기능 구현을 요청했다.
OpenAI API 기준 구현을 선택했다. 별도 원문이나 API 키를 전달받은 것은 아니다.
문구 경로와 사진 경로는 독립 분석하고 결과에서만 합류한다.
Cosmetic_RAG_AI_Handoff는 로컬 PostgreSQL/pgvector의 RAG 자료와 계획 기준이며 원문 및 완성 DB가 아니다.
이 단계에서는 feat/non-vector-mvp에 작업했다. 아래 최신 요청에 따라 main 반영까지 진행한다.
기능별 구현과 검증 결과는 이번 작업 완료 후 docs/07-validation.md에 기록한다.

## 현재 연결 수준

- 제공 Vue 시안을 실제 /api/v1과 연결했다. 업무 이력은 서버 DB이며 브라우저의 mock engine을 호출하지 않는다.
- 회원과 기업 및 세션, 파일 임시 보관, 문구와 사진 분리 Graph, 증빙 추출 MCP, 공식 조회 adapter, 승인 재개와 버전 및 삭제가 현재 범위다.
- 로컬 Docker 검증에서 합성 HTTP 전체 경로와 pgvector 및 역할 권한을 확인했다. 실제 키를 이용한 외부 추론이나 정상 공공 API 조회는 실행하지 않았다.
- 모델 미설정과 RAG 미연결은 명시적으로 표시한다. 다건 Worker, 의미 대조와 과금은 후속이다. 단건 진행은 2026-10-21부터 검토별 SSE로 전달한다.
- Handoff는 원문 없는 9개 후보 자료 목록이었다. 후속 점검에서 공개 PDF 6건과 기본 청크 294개를 확보했다. knowledge는 빈 상태이며 임베딩과 검색 연결은 후속이다.


## 최신 사용자 요청: main 반영과 실제 입력 검증

- 사용자는 단건 최소 기능과 공통 문서를 GitHub main에 반영하라고 명시했다. 이전 로컬 커밋까지만 진행한다는 범위보다 최신 지시를 우선한다.
- 노션 기획서와 ERD v2를 다시 확인했다. 주요 PostgreSQL 1개와 별도 과금 DB의 목표, refresh/reset과 잠금 및 감사 기능의 미구현 차이를 docs/04-data.md에 기록한다.
- 팀원은 두 저장소의 main을 clone해 이어서 개발한다. Desktop 폴더를 필수 입력으로 요구하지 않고 manifest, API schema 및 기본 점검 코드를 Git에 둔다.
- 제공한 실제 긴 이미지 2장과 모공 비교 이미지 1장을 사용한다. 25 MiB 파일과 전체 75 MiB 한도로 보완했다. 이미지 원본은 Git에 올리지 않는다.
- 검증 관리자 계정은 자기 기업의 admin이다. 공용 비밀번호나 타 기업 권한 우회용 backdoor를 만들지 않는다.
- 키 파일을 프로그램이 직접 읽는 것은 허용됐지만 실제 키가 전달되거나 설정됐다고 가정하지 않는다. 값은 채팅이나 로그에 출력하지 않는다.
- 노션의 근거 문서 9건과 API 4종은 별개 목록이다. HTTP 200, 문서 다운로드 및 기본 청킹, API 승인키 실응답을 구분한다.
- 실제 원문 수집 및 기본 청킹 미리보기는 추가했지만 임베딩, knowledge 적재와 RAG 검색은 여전히 미연결이다.
