# 서비스 아키텍처와 분할

기준은 [노션 기획서의 5장과 6장](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)이다.
아래 목표 구조와 현재 실행 기반을 구분한다.

## 목표 계층 구조

~~~mermaid
flowchart TB
  subgraph Presentation
    UI["Vue 3 / Vite / TypeScript / Pinia"]
  end
  subgraph Application
    API["Spring Boot 3 / BFF / JWT / Tenant / SSE"]
    QUEUE["다건 큐 / Redis 또는 SQS"]
    BILL["별도 과금 서비스 / 잔액 확인과 확정 차감"]
  end
  subgraph Orchestration
    WORKER["다건 Worker / 라이브러리 미확정"]
    AGENT["FastAPI / LangGraph / Guardrail / Recovery / HITL"]
  end
  subgraph Tool
    BUSINESS["business-mcp / 증빙, 식약처, 특허"]
    RAG["rag-mcp / rules와 cases 분리 검색"]
  end
  subgraph Data
    APP[("PostgreSQL / 제품과 검토 이력")]
    STATE[("PostgreSQL / 체크포인트")]
    VECTOR[("PostgreSQL + pgvector / 공개 근거")]
    RAW[("Redis 임시 캐시 / 업로드 원본")]
    LEDGER[("별도 과금 DB / append-only ledger")]
    EXT["식약처 API / KIPRIS Plus"]
  end
  UI -->|"HTTPS / REST / SSE"| API
  API -->|"단건 내부 HTTP"| AGENT
  API -->|"다건 202"| QUEUE
  QUEUE --> WORKER --> AGENT
  API --> APP
  API --> RAW
  API -->|"잔액 사전확인"| BILL --> LEDGER
  API -.->|"완료 사용량 이벤트 / 전달 경로 후속 설계"| BILL
  AGENT -->|"MCP"| BUSINESS
  AGENT -->|"MCP"| RAG --> VECTOR
  BUSINESS -->|"내부 API"| API
  BUSINESS --> EXT
  AGENT -->|"유일한 직접 DB 접근 예외: checkpointer"| STATE
  AGENT -->|"진행과 결과 이벤트"| API
  AGENT --> LLM["외부 LLM API"]
  INDEX["공개 문서 수집 / 파싱 / 청킹 / 임베딩"] --> VECTOR
~~~

과금 이벤트의 정확한 전달 방식과 Worker 라이브러리는 후속 설계 항목이다.
과금 판단과 원장 갱신을 LLM이나 MCP 도구에 맡기지 않는다.
잔액 사전확인은 병렬 요청의 중복 소비를 막는 예약과 동일하지 않으므로 과금 구현 시 별도로 설계한다.

## 현재 실행 기반

| 실행 단위 | 현재 스택 | 현재 역할 |
| --- | --- | --- |
| frontend | Vue 3, TypeScript, Vite, Router, Pinia, Node 24 | 실제 연결 상태 확인 화면 |
| services/api | Java 21, Spring Boot 3.5.16, JDBC, Flyway | DB와 Agent 연결, 내부 토큰 경계 |
| services/agent | Python 3.12, FastAPI, FastMCP Client | 인증된 MCP 목록 조회, 체크포인트용 DB 연결 확인 |
| services/business-mcp | Python 3.12, FastMCP 4.1.1, httpx | Backend 내부 API 연결 확인 |
| services/rag-mcp | Python 3.12, FastMCP 4.1.1, Pydantic, psycopg | 검색 계약, pgvector 연결 확인 |
| postgres | PostgreSQL 17, pgvector 0.8.7 | app, agent, knowledge 스키마와 권한 |
| redis-cache | Redis 7.4 | 비영구 원본 캐시 준비, 업로드 연동 미구현 |
| redis-queue | Redis 7.4, queue 프로필 | 별도 영구 큐 서버 준비, 발행과 Worker 미구현 |
| 과금 서비스와 DB | 스택 상세 미확정 | 현재 실행 서비스 없음 |

LangGraph는 선택 의존성으로 잠갔지만 그래프와 체크포인터는 아직 없다.
JWT, SSE, Redis 파일 업로드, 큐 발행, 과금도 실행환경 준비와 구분한다.

## 책임과 데이터 접근

- Frontend는 Backend API만 호출한다. MCP, DB, 내부 토큰에 접근하지 않는다.
- Backend는 로그인과 기업 권한, 검토 이력 조회 및 삭제, 광고 버전 관리를 소유한다. 이력 CRUD를 MCP에 추가하지 않는다.
- Agent는 처리 순서, 도구 선택과 판단, HITL 중단과 재개를 소유한다. 업무 데이터는 MCP로 접근한다.
- Agent의 DB 직접 접근은 LangGraph 실행 상태와 HITL 체크포인트에만 허용한다. 현재 agent_user도 agent 스키마에만 권한이 있다.
- business-mcp는 증빙 구조화, 제품 대조와 식약처 및 특허 검증을 전담한다. 업무 DB에 직접 접근하지 않는다.
- rag-mcp는 법령과 기준, 보조 사례를 검색하고 출처와 버전을 반환한다. 특허 검증을 중복 구현하지 않는다.
- 임시 원본 캐시와 영구 큐는 Redis 인스턴스를 나눠 원본 파일이 큐 AOF에 남지 않게 준비했다. 이는 노션 요구를 구현하기 위한 기반 선택이다.
- 공개 문서 색인은 사용자 요청과 별도 배치다. 문서와 임베딩을 Git에 저장하지 않는다.

## 단건과 다건 목표 흐름

~~~mermaid
sequenceDiagram
  participant U as Vue
  participant B as Spring Boot
  participant Q as Queue / Worker
  participant A as Agent
  participant M as MCP 서버
  U->>B: 제품, 광고와 증빙 입력
  B->>B: 인증, 기업 권한, 잔액 확인
  alt 단건
    B->>A: 내부 HTTP로 즉시 실행
  else 다건
    B->>Q: 건별 작업 적재
    B-->>U: 202와 작업 ID
    Q->>A: Worker가 작업 실행
  end
  A->>M: 기준 검색, 증빙과 공공 API 대조
  M-->>A: 출처, 버전, 조회 상태
  A-->>B: 검토 초안과 승인 대기 이벤트
  B-->>U: SSE 진행과 미승인 결과
  U->>B: 권한 있는 담당자의 승인 또는 수정 요청
  B->>A: 해당 버전과 checkpoint 재개
  A-->>B: 리포트 데이터와 처리 완료
  B->>B: 결과 저장, 과금 이벤트 전달
  B-->>U: 결과와 이력
~~~

이 시퀀스는 목표 설계다. 현재 작업 엔드포인트는 501이며 작업 실행을 흉내 내지 않는다.
단건 HTTP 응답 종료와 SSE 및 HITL 대기 간 수명 정책은 Agent와 Backend 담당자가 합의한다.

## 네트워크

개발 포트는 127.0.0.1에만 바인딩한다. Compose 내부에서는 서비스 이름을 사용한다.
정적 Bearer 토큰은 개발용 서비스 인증이며 사용자 인증이나 기업 권한을 대체하지 않는다.
