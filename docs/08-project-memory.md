# 프로젝트 메모리

마지막 기준 확인: 2026-10-20.
원본: [화장품 광고 컴플라이언스 Agent 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d).
이 파일은 저장소에서 공유하는 작업 맥락이다. 계정 전체의 별도 메모리를 갱신했다는 의미는 아니다.
기획서 본문과 연결된 REST, MCP, Graph 및 시퀀스를 브라우저에서 다시 읽었다. Notion 연동 API는 해당 페이지를 반환하지 않아 브라우저 공개 본문을 사용했으며 노션 자체는 수정하지 않았다.

## 유지할 사용자 의도

- B2B 화장품 광고 사전검토 서비스이며 사용자는 rag-mcp 담당이다.
- 이번 요청은 팀 분업을 위한 개발환경, 공통 문서와 계약의 기초를 만드는 것이다.
- 광고 판정, 실검색과 서비스 전체를 대신 완성하지 않는다.
- 제품 정보의 원본은 노션이다. 첨부 그림과 과거 대화는 보조 참고다.
- 직접 작성 자료에 중앙점을 쓰지 않는다. 기존 파일은 그 파일에 반영한다.
- 사용자가 프로젝트 방법을 배우며 직접 개발한다. 전체 기능을 대신 완성하기보다 작업별 계약, 작은 구현, 검증과 동료 리뷰 순서로 진행한다.
- 광고 입력은 PDF와 이미지 두 종류다. 직접 카피 입력은 제외한다. 텍스트와 스캔 PDF 및 긴 이미지는 내부 처리 유형이다.

## 최신 노션으로 확인한 경계

- Frontend Vue, Backend Spring Boot, Agent Python과 LangGraph, 두 MCP, PostgreSQL과 pgvector, Docker Compose.
- rag-mcp는 법령과 기준 rules, 사례 cases를 분리해 검색한다. 사례는 확보한 경우에만 보조로 제공하고 출처 검증 상태를 표시한다.
- business-mcp는 증빙 구조화와 제품 대조, 식약처 조회, 특허 조회 및 원문 대조를 맡는다.
- 검토 이력 조회와 삭제는 Backend가 JDBC로 처리한다. business-mcp에 이력 CRUD를 넣지 않는다.
- Agent 업무 데이터 접근은 MCP를 거친다. checkpointer만 전용 DB 스키마 직접 접근을 허용한다.
- 결과와 승인이력은 영구 저장을 기본으로 한다. 원본 이미지와 스캔본은 처리 동안만 Redis에 보관하고 폐기한다.
- 과금은 Agent와 MCP 밖의 별도 결정론적 서비스와 DB다. 잔액 확인, 확정 차감과 원장이 목표이며 실결제는 목업이다.
- 수정된 승인 광고는 새 버전이고 이전 승인을 이어받지 않는다.
- FR-14와 FR-15는 특허 확인과 내용 대조다. 식약처 미조회나 API 실패를 부적합 또는 제품 부재로 단정하지 않는다.

## 바뀐 오래된 가정

- Redis Queue/Celery 또는 SQS 후보를 유지하던 이전 저장소 문서는 폐기한다. 최신 노션은 RabbitMQ로 확정했다.
- 고객 업로드 원본을 일반 파일 영역이나 S3에 영구 보관한다는 이전 제안을 현재 보존 정책으로 사용하지 않는다.
- 특허 대조를 rag-mcp에 배정하거나 이력 삭제를 business-mcp에 배정하지 않는다.
- 단건도 반드시 202라는 가정은 확정되지 않았다. 다건 202는 요구사항이고 단건 내부 큐 통일은 대안이다.

## 구현 선택과 미확정

- 개발용 FastMCP 4.1.1, Python 3.12, Java 21, PostgreSQL 17을 잠갔다. 노션의 필수 버전이라고 주장하지 않는다.
- BAAI/bge-m3와 vector(1024)는 준비값이다. 모델을 실행하거나 실문서를 적재하지 않았다.
- 임시 원본은 Redis 캐시, 다건 큐는 RabbitMQ로 분리한다. RabbitMQ는 선택 queue 프로필이며 Worker 라이브러리와 업무 큐 계약은 남았다.
- Git 흐름은 main과 기능 브랜치, Draft PR을 제안했다. develop 및 브랜치 보호는 적용하지 않았다.
- Agent State와 세부 도구 시그니처, 외부 API 실응답 검증, 실패 건 과금 정책은 노션 7장의 후속 결정이다.
- Redis 파일 크기와 TTL, 캐시 소실 후 배치 재시도, 과금 이벤트 전달과 멱등성도 구현 시 합의한다.

## 다음 RAG 작업

출처 목록과 버전 확인 → 표 및 예외 조건을 보존한 파싱 → rules/cases 구분 적재
→ 임베딩 → 키워드와 벡터 검색 → 출처 원문 반환 → 검색 평가 → Agent MCP 통합.
search_rules, search_cases와 get_evidence는 현재 NOT_IMPLEMENTED이며 가짜 근거를 반환하지 않는다.
구체적인 시작 절차는 docs/06-rag-start.md를 따른다.

## 입력 흐름과 분업 보완

- 2026-10-19 사용자 요청으로 입력, PDF 파싱, OCR, Vision, MCP와 HITL 및 보존까지 설명용 인터랙션을 작성한다. 실제 업무 UI나 유료 API 호출은 요청 범위가 아니다.
- 노션은 Vision 또는 OCR + LLM을 열어두었다. 기준 구현안은 business-mcp의 공통 문서 준비와 Agent의 Vision adapter다. prepare_input은 아직 없는 제안 도구다.
- 텍스트 PDF는 로컬 파서로 읽고 스캔 PDF와 이미지는 렌더 또는 이미지 정규화 후 Vision으로 읽는다. 정밀 좌표는 로컬 OCR 확장이 필요하다.
- 로컬 파서와 로컬 OCR은 외부 키가 없고 Vision 및 텍스트 LLM 키는 Agent 서버에만 둔다. 특정 공급자와 모델 확정은 별도다.
- 판독 불명은 WAITING_INPUT, API 미조회와 장애는 각각 NOT_FOUND와 LOOKUP_FAILED로 구분하는 후속 계약을 제안했다. 기존 코드의 응답으로 과장하지 않는다.
- 원본을 폐기하면 새 승인자와 과거 이력의 이미지 열람을 항상 지원할 수 없다. 같은 해시의 원본 재업로드 또는 승인 종료까지의 수명 변경을 팀이 결정해야 한다.
- Git 운영안과 PR 템플릿을 보완하며 실제 분업은 docs/10-work-breakdown.md의 역할, 작업 카드와 통합 순서를 따른다.

## 2026-10-20 개발 기반 점검

- GitHub의 Backend와 Frontend PR #1은 모두 OPEN, Draft이며 main에 미병합임을 읽기 확인했다.
- 현재 실행 소스는 원격 Head와 같고 기능별 파일 경계가 있다. 모든 담당자가 전체 서비스를 항상 실행할 필요는 없다.
- Frontend는 Vue Router, Pinia, api와 views의 최소 분리만 구현했다. 업로드와 검토 UI, 업무용 OpenAPI와 Mock은 후속이다.
- Python 10개, 계약 JSON 일치, Java verify와 Frontend 빌드, Compose 설정 검사를 다시 통과했다. 전체 컨테이너 기동과 smoke를 이번 점검에서 재실행한 것으로 표시하지 않는다.
- Queue는 선택 실행이며 과금 서비스와 DB는 현재 존재하지 않는다. 단건 경로를 먼저 통합한다.
- 입력 두 종류, 현재 기반 브랜치로 시작하는 clone과 Frontend 구조 설명을 기존 문서에 정정했다. 이 로컬 문서 변경은 커밋과 push 전까지 원격 PR에 반영된 것으로 표시하지 않는다.

## 최신 전체 아키텍처 반영

- 사용자 정정: RabbitMQ만 중심으로 개발하지 않는다. Vue, Backend, Agent, Business MCP, RAG MCP와 Data 계층을 함께 준비한다.
- 다건 목표는 Backend → Agent API → RabbitMQ → Agent Worker다. 단건 큐 통일은 여전히 팀 합의 전이다.
- Agent의 탐지, 근거 검색과 증빙 및 공공 API 대조는 도구선택 loop다. 노션의 번호를 모든 도구의 고정 실행 순서로 해석하지 않는다.
- 최신 MCP 초안 이름은 search_regulations, search_precedents다. 실행 코드의 search_rules/search_cases v0.1과 차이를 기록하고, 초안 합의 후 버전 있는 계약 PR로 전환한다.
- 특허 초안은 getWordSearch 응답의 명칭, 초록과 IPC를 대조하며 청구항 확보 전 청구항 검증을 완료했다고 말하지 않는다.
- Frontend의 PDF 또는 이미지 드래그앤드롭 → 명시적인 시작 버튼은 README의 목표만 추가한다. 이번 작업에 업무 화면과 업로드 동작 구현은 포함하지 않는다.
- RabbitMQ 설정은 독립 계정, vhost, localhost 포트, 볼륨과 healthcheck다. 합성 큐 연결 검사와 실제 업무 publisher, Worker 및 DLQ 구현을 구분한다.

## 이번 환경 검증

- Python 12개, Java 2개와 Maven verify, Frontend 타입 검사 및 빌드를 통과했다.
- 별도 Compose 검증 프로젝트에서 8개 서비스를 실제 실행하고 Vue 프록시, HTTP와 MCP, pgvector 권한을 검사했다.
- RabbitMQ 합성 AMQP 검사는 confirm, 수신, nack/requeue와 재전달, manual ack를 통과했다. 업무 배치 구현과 다르다.
- 기존 두 저장소의 chore/dev-foundation과 Draft PR #1을 재사용한다. main 병합과 브랜치 보호 변경은 하지 않는다.
