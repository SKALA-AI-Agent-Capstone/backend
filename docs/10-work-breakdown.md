# 담당별 개발 계획과 통합 순서

> 기준: [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d).
> 아래 작업 ID와 디렉터리는 개발 제안이다. 담당자 이름, 일정과 GitHub Issue를 생성하거나 확정한 문서가 아니다.

## 1. 분업 원칙

폴더별로 코드를 쓴 뒤 마지막 날 연결하는 방식으로 진행하지 않는다.
공통 입력과 오류 응답을 먼저 정하고, 합성 입력으로 한 번 끝까지 연결한 후 각 기능을 실제 구현으로 교체한다.
한 사람이 여러 역할을 맡을 수 있지만 책임 경계와 PR은 구분한다.
제품의 광고 입력은 PDF와 이미지 두 종류다. 합성 Claim과 SourceBlock은 독립 개발을 위한 테스트 데이터이며 직접 카피 입력 UI가 아니다.

| 역할 | 소유 범위 | 받는 입력 | 넘기는 출력 | 중복 구현하지 않을 것 |
| --- | --- | --- | --- | --- |
| Frontend | 업로드, 추출 확인, 진행, 검토와 승인, 이력 화면 | HTTP와 SSE 계약 | 입력 파일과 사용자 결정 | LLM 호출, 서버 키와 법령 판정 |
| Backend | 인증, 기업 권한, asset과 버전, 작업, 결과와 이력 | 사용자 요청과 내부 이벤트 | 인증된 실행 문맥과 업무 데이터 | 문서 의미 분석과 RAG 검색 |
| 입력 처리 | business-mcp의 파서와 이미지 준비, Agent Vision adapter | 인증된 asset 참조 | 추출 블록, 페이지, 시각 관찰과 품질 상태 | 공개 법령 색인, 법률 판정 |
| Agent | 노드와 분기, 도구 호출, 주장과 판단, HITL | 추출 결과와 도구 결과 | 검토 초안, 이벤트와 승인 후 리포트 데이터 | 업무 DB 직접 조회와 과금 |
| Business MCP | 제품과 증빙 대조, 식약처와 특허 조회 및 대조 | 주장, 제품과 자료 참조 | 일치와 불일치 및 확인 불가, 출처 | 검토 이력 CRUD와 법령 검색 |
| RAG MCP | 공개 문서 수집, rules와 cases 검색, 인용 | 주장과 문맥, 적용일 | 원문과 조건, 출처와 버전 | 고객 광고 OCR, 특허 검증과 승인 |
| 큐 및 과금 | Agent API 발행과 다건 Worker, 사용량 전달, 별도 원장 | 건별 작업과 사용량 이벤트 | 실행 상태, 잔액과 멱등 차감 | LLM 판단 |

입력 처리 역할은 별도 세 번째 MCP 서버를 뜻하지 않는다. 공통 파서 구현은 Business MCP 쪽, Vision 모델 호출과 흐름 제어는 Agent 쪽 담당자가 협업한다.

## 2. 코드 배치

현재 존재하는 서비스 폴더 아래에 다음 모듈을 순차적으로 추가하는 안이다.
존재하지 않는 경로를 이미 구현된 코드로 표시하지 않는다.

~~~text
frontend/src/
  api/                       HTTP와 SSE 연결
  stores/                    작업과 승인 상태
  views/                     입력, 검토, 이력
  components/review/         원본 뷰어와 근거, 추출 확인

backend/services/api/src/main/java/org/skala/capstone/
  auth/                      사용자와 기업 권한
  assets/                    업로드 검증, Redis와 asset 수명
  reviews/                   작업, 광고 버전, 결과와 이력
  events/                    진행 이벤트와 SSE

backend/services/agent/src/capstone_agent/
  nodes/                     입력, 주장, 검색, 대조, 초안, 승인과 리포트
  providers/                 Vision과 텍스트 모델 adapter
  state/                     Graph State와 체크포인트

backend/services/business-mcp/src/capstone_business_mcp/
  ingestion/                 PDF 파서, 렌더, 이미지와 선택 OCR
  tools/                     문서 준비, 제품과 증빙, 식약처와 특허
  clients/                   Backend 내부 API와 외부 API adapter

backend/services/rag-mcp/src/capstone_rag_mcp/
  ingest/                    공개 출처 수집과 파싱, 청킹, 색인 배치
  retrieval/                 필터, 키워드와 벡터 검색, 순위 조정
  evaluation/                검색과 인용 평가

backend/packages/shared/     Pydantic과 공통 계약
backend/contracts/           생성된 JSON Schema
~~~

큐 Worker와 과금 서비스의 실제 경로와 언어는 후속 결정이다. 빈 서비스만 늘리지 않는다.

### 현재 Frontend 기반과 후속 구조

| 구분 | 현재 존재 | 기능 개발 때 추가할 것 |
| --- | --- | --- |
| 화면 | views/EnvironmentView.vue, WorkspacesView.vue | 업로드, 검토와 승인, 이력 화면 |
| 상태 | stores/environment.ts | 작업, 추출 확인과 승인 상태 |
| HTTP | api/environment.ts | 파일 업로드, 작업과 결과 API client |
| 라우팅과 프록시 | router.ts, vite.config.ts | 업무 경로와 인증 처리 |
| 재사용 UI와 Mock | 업무용 구성 없음 | 필요한 원본 뷰어와 근거 컴포넌트, 계약 기반 Mock |

Vue Router, Pinia와 API client를 나눈 최소 기반이다. 업무용 OpenAPI와 Mock은 아직 제공하지 않는다.
Frontend와 Backend 담당자가 먼저 요청과 응답, 진행 및 오류 예제를 합의하고 구현한다.

## 3. 작업 카드

### FE: Frontend

| ID | 작업 | 선행 계약 | 완료 조건 |
| --- | --- | --- | --- |
| FE-01 | 제품과 광고 및 증빙 입력 | asset, 업로드 오류, 광고 버전 | 형식과 용량 오류를 표시하고 서버 오류도 처리 |
| FE-02 | 원본과 추출 확인 | block, 좌표 출처, 품질 상태 | 페이지와 문구를 연결하고 좌표가 없으면 정밀 박스를 표시하지 않음 |
| FE-03 | 진행과 재시도 | task 상태와 SSE | 재접속 후 저장된 이벤트부터 복구, 실패 건만 재시도 |
| FE-04 | 근거와 검토 및 HITL | finding, 근거, 승인 명령 | 작성자와 승인자 권한 구분, 불명확한 결과를 자동 승인으로 표시하지 않음 |
| FE-05 | 이력과 재다운로드 | 버전과 결과 스냅샷 | 원본 폐기 시 안내, 새 광고 버전에 이전 승인 미적용 |

### APP: Backend

| ID | 작업 | 산출물 | 완료 조건 |
| --- | --- | --- | --- |
| APP-01 | 인증과 기업 권한 | User와 Tenant, 역할, 내부 인증 | 다른 기업의 task와 asset 접근 거부 |
| APP-02 | 업로드와 Redis 수명 | asset ID, 해시, 파일 검증과 내부 읽기 | 손상과 암호 PDF 거절, 원본을 로그와 DB에 중복 보관하지 않음 |
| APP-03 | 작업과 광고 버전 | 단건 실행과 다건 접수, command_id | 재전송 시 중복 작업 생성 방지, 다건 202 |
| APP-04 | 이벤트와 승인 API | SSE, 승인 명령과 감사 기록 | 승인 버전 재검증, 중복 승인 요청과 연결 끊김 처리 |
| APP-05 | 이력과 삭제, 리포트 재생성 | 영구 결과와 삭제 API | 저장된 근거 버전으로 재생성, 삭제 시 체크포인트와 캐시 정리 |

### ING: 입력 처리, Agent와 Business MCP 공동 인터페이스

| ID | 작업 | 산출물 | 완료 조건 |
| --- | --- | --- | --- |
| ING-01 | 입력 데이터 계약 | PreparedInput과 SourceBlock 제안 | 광고와 증빙 역할, 페이지, 원문과 품질을 구분 |
| ING-02 | 로컬 PDF와 이미지 준비 | prepare_input 도구와 내부 파서 | 텍스트, 스캔, 혼합 PDF와 긴 이미지의 변환 기록 |
| ING-03 | Agent Vision adapter | 모델 입력과 형식 검증 | 키는 서버에만 존재, 이미지 문자를 추출하고 판독 불가를 반환 |
| ING-04 | 위치와 사용자 확인 | 좌표 또는 페이지 앵커 | 정밀 좌표가 없는 Vision 결과를 정확한 박스로 보이지 않게 함 |

### AGT: Agent

| ID | 작업 | 산출물 | 완료 조건 |
| --- | --- | --- | --- |
| AGT-01 | Graph State와 공통 결과 | run, claim, finding과 error | 코드와 docs/03-contracts.md가 일치 |
| AGT-02 | 주장 추출과 MCP 호출 | 원문과 각주를 보존한 Claim | 불명확한 숫자에서 WAITING_INPUT, 임의 주장 생성 방지 |
| AGT-03 | 근거 종합과 리라이트 | 주장별 Finding | 근거 부족과 조회 장애를 구분, 원문 인용과 모델 설명 분리 |
| AGT-04 | HITL와 재개 | interrupt와 checkpoint | HTTP 연결을 닫아도 재개, 수정 버전 승인 거부 |
| AGT-05 | 리포트와 사용량 전달 | 최종 결과와 사용량 이벤트 | 저장 및 전송 재시도로 중복 비용 처리가 발생하지 않음 |

### BIZ: Business MCP

| ID | 작업 | 산출물 | 완료 조건 |
| --- | --- | --- | --- |
| BIZ-01 | 제품과 증빙 대조 | 제품, 시험 대상과 기간, 주장별 근거 | 원료 자료와 완제품 자료를 구분하고 누락 서류 목록 반환 |
| BIZ-02 | 식약처 API adapter | 공식 실응답과 내부 표준 결과 | FOUND, NOT_FOUND와 LOOKUP_FAILED 구분 |
| BIZ-03 | 특허번호 확인 | 번호 정규화와 조회 | 번호 인식 불명확 시 확인 요청, 등록 상태와 미조회 구분 |
| BIZ-04 | 특허 원문 대조 | 원문 위치와 관련 범위 | 원문 미확보를 불일치로 단정하지 않음 |

### RAG: 사용자 담당의 독립 작업

| ID | 작업 | 산출물 | 완료 조건 |
| --- | --- | --- | --- |
| RAG-01 | 출처 목록 | URL, 종류, 발행기관과 버전 목록 | rules와 cases 및 적용 범위를 분류 |
| RAG-02 | 파싱과 청킹 | 문서 버전, 원문, 페이지와 예외 조건 | 표의 본문과 비고를 함께 복구, 구버전 덮어쓰기 금지 |
| RAG-03 | 임베딩과 적재 | 모델 리비전과 corpus_version | 문서와 질의의 동일 임베딩 규약, 역할별 접근 권한 |
| RAG-04 | 검색 구현 | search_rules, search_cases, get_evidence | 검토일 필터, 두 컬렉션 구분, 저장 원문 그대로 인용 |
| RAG-05 | 평가와 MCP 통합 | 기대 문서와 평가 결과 | 근거 없음과 장애, 조건 누락, 광고 페이지와 근거 페이지 혼동 방지 |

RAG는 이미지나 PDF 광고를 받는 API를 기다릴 필요가 없다.
현재 SearchRulesInput 형식의 합성 주장으로 개발하고 이후 Agent가 같은 입력을 보내게 연결한다.
임베딩 모델과 코퍼스 버전은 결과에 남기며 검색 점수를 위법 확률로 표시하지 않는다.

### OPS: 큐와 과금

| ID | 작업 | 완료 조건 |
| --- | --- | --- |
| OPS-01 | Agent API → RabbitMQ → Agent Worker와 상태 전달 | confirm과 수동 ack, 제한 재시도와 DLQ, 중복 실행 통제, 캐시 소실 시 원본 재확보 |
| OPS-02 | 잔액과 사용량 원장 | 별도 DB, 중복 이벤트 차감 방지, 목업 충전 |
| OPS-03 | 오류 및 비용 정책 합의 | 중도 실패와 HITL 대기 과금 시점, 동시 요청 정책 문서화 |

## 4. 실제 통합 순서

| 순서 | 연결할 흐름 | 확인할 증거 |
| --- | --- | --- |
| 1 | 합성 Claim → 실제 rag-mcp → 근거 | 저장 원문과 출처가 MCP로 돌아옴 |
| 2 | 내부 합성 SourceBlock → Agent → RAG → 검토 초안 | 파일 처리 전 계약 검증. 사용자 직접 카피 입력 기능이 아님 |
| 3 | 광고 이미지 → 문서 준비 → Vision → 같은 Claim 경로 | 이미지로 입력해도 아래 검색 계약은 바뀌지 않음 |
| 4 | 제품과 증빙 및 공공 API 대조 추가 | 누락 자료, API 미조회와 장애를 별도로 표현 |
| 5 | 권한 있는 HITL → 결과 저장과 PDF | 재시작 후 재개, 원본 소실과 새 버전 처리 |
| 6 | 다건 Worker와 별도 과금 연결 | 건별 실패와 중복 이벤트를 실제로 재현 |

## 5. 공유 파일 변경 규칙

- packages/shared, contracts와 DB 스키마 변경은 소비자 담당자와 먼저 합의한다.
- 같은 입력 파서와 Vision 호출을 각 서비스에서 별도로 복사하지 않는다.
- 공통 계약 PR과 실제 기능 PR을 구분하고 종속 PR을 연결한다.
- .env, 실제 고객 파일과 API 응답의 비공개 부분을 PR에 포함하지 않는다.
- 기능별 정상 예제뿐 아니라 이 문서의 완료 조건에 있는 실패 예제를 PR 검증에 포함한다.
- 담당자 수에 맞춰 역할을 합쳐도 경계와 완료 조건은 그대로 유지한다.

## 6. 최신 노션과 초기 세팅의 연결

| 역할 | 지금 준비된 실행 기반 | 다음 구현 경계 |
| --- | --- | --- |
| Frontend | Vue api, stores, views, router와 Backend proxy | README의 PDF 및 이미지 선택과 시작 버튼 목표, 이후 업무 계약에 맞춰 화면 구현 |
| Backend | Spring Boot, JDBC, Flyway와 내부 인증 | 업로드와 기업 권한, reviews API, 진행과 이력 |
| Agent | FastAPI, 인증된 MCP Client와 전용 DB 연결 | 입력 파싱, 도구선택 loop, HITL, 다건 publisher와 Worker |
| Business MCP | FastMCP와 Backend 내부 호출 | 증빙 구조화, 제품 대조와 공식 API 검증 |
| RAG MCP | FastMCP, pgvector 스키마와 읽기 전용 계정 | 최신 search_regulations 및 search_precedents 초안과 기존 계약 전환, 수집과 실제 검색 |
| 공통 환경 | Compose, 잠금 파일, Redis와 RabbitMQ, CI 및 smoke | 필요한 계약과 서비스만 단계적으로 추가 |

Agent의 탐지, 검색과 대조는 노션 Graph 초안의 제한된 도구선택 loop로 구현한다.
증빙이 없으면 자료 안내, 특허가 없으면 특허 도구 생략 등 조건을 포함한다.
노션의 처리 번호를 모든 MCP 도구의 고정 순서로 구현하지 않는다.
RabbitMQ는 작업을 전달하며 LLM 판단, OCR, 근거 검색과 과금 로직을 대신하지 않는다.
