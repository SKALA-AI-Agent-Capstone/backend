# 서비스 아키텍처와 분할

기준은 [노션 기획서의 5장과 6장](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)이다.
2026-10-20에 본문과 연결된 Graph 및 시퀀스 초안을 확인했다.
아래 목표 구조와 현재 실행 기반을 구분한다. RabbitMQ는 전체 구조의 다건 전달 구성 요소다.

## 목표 계층 구조

~~~mermaid
flowchart TB
  subgraph Presentation
    UI["Vue 3 / Vite / TypeScript / Pinia"]
  end
  subgraph Application
    API["Spring Boot 3 / BFF / JWT / Tenant / SSE"]
    BILL["별도 과금 서비스 / 잔액 확인과 확정 차감"]
  end
  subgraph Orchestration
    subgraph AGENT_SERVICE["하나의 Agent 서비스 / 내부 역할"]
    AAPI["Agent API / FastAPI / 실행 접수와 다건 발행"]
    WORKER["Agent Worker / 라이브러리 미확정"]
    AGENT["LangGraph / 도구선택 loop / Guardrail / Recovery / HITL"]
    end
    QUEUE["RabbitMQ / 다건 메시지 전달"]
  end
  subgraph Tool
    BUSINESS["business-mcp / 증빙 구조화, 제품 대조, 식약처, 특허"]
    RAG["rag-mcp / rules와 cases 분리 검색"]
    PARSER["순수 문서 파서 / 역할별 내부 호출, 공유 가능"]
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
  API -->|"내부 HTTP / 단건과 다건 접수"| AAPI
  AAPI -->|"단건 실행"| AGENT
  AAPI -->|"다건 발행 확인"| QUEUE
  QUEUE --> WORKER --> AGENT
  API --> APP
  API --> RAW
  API -->|"잔액 사전확인"| BILL --> LEDGER
  API -.->|"완료 사용량 이벤트 / 전달 경로 후속 설계"| BILL
  AGENT -->|"MCP"| BUSINESS
  AGENT -->|"MCP"| RAG --> VECTOR
  BUSINESS -->|"내부 API"| API
  BUSINESS --> PARSER
  AGENT -->|"광고 입력 파싱"| PARSER
  BUSINESS --> EXT
  AGENT -->|"유일한 직접 DB 접근 예외: checkpointer"| STATE
  AGENT -->|"진행과 결과 이벤트"| API
  AGENT -->|"준비된 이미지와 텍스트 / HTTPS"| LLM["Vision과 텍스트 LLM API / 서버 키"]
  INDEX["공개 문서 수집 / 파싱 / 청킹 / 임베딩"] --> VECTOR
~~~

다건 발행 주체는 연결된 시퀀스의 Agent API를 따른다. Backend와 Agent가 접수 및 발행 상태를 맞추는 계약은 후속이다.
단건 큐 통일은 미확정이며 단건을 내부 HTTP로 실행하는 경로를 유지한다.
과금 이벤트의 정확한 전달 방식과 Worker 라이브러리는 후속 설계 항목이다.
과금 판단과 원장 갱신을 LLM이나 MCP 도구에 맡기지 않는다.
잔액 사전확인은 병렬 요청의 중복 소비를 막는 예약과 동일하지 않으므로 과금 구현 시 별도로 설계한다.

노션 Graph의 광고 입력 파싱과 Vision 호출은 Agent 입력 노드 책임이다. 증빙 파싱은 business-mcp.structure_evidence가 맡는다.
같은 PDF 파서의 순수 함수를 공유할 수 있지만 광고 입력을 위해 prepare_input이라는 새 MCP 도구를 필수로 만들지 않는다.
Backend가 인증된 파일 참조를 전달하고 원본 접근을 검증한다. Agent가 Redis에 직접 접속하는 경로는 추가하지 않는다.
파일 참조를 읽고 페이지 콘텐츠를 전달하는 내부 API는 구현할 계약이며 현재 존재하지 않는다.
PDF 문자 추출, 이미지 OCR과 Vision 분석의 차이는 [입력 처리 설계](09-input-pipeline.md)에 정의했다.

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
| rabbitmq | RabbitMQ 4.3.6, queue 프로필 | 계정, vhost, 볼륨과 연결 검사, 업무 발행 및 Worker 미구현 |
| 과금 서비스와 DB | 스택 상세 미확정 | 현재 실행 서비스 없음 |

LangGraph는 선택 의존성으로 잠갔지만 그래프와 체크포인터는 아직 없다.
JWT, SSE, Redis 파일 업로드, 큐 발행, 과금도 실행환경 준비와 구분한다.
pdfplumber의 고객 입력 연동, PDF 렌더와 OCR, Vision SDK와 API 호출은 미구현이다.
입력 처리 예시 화면은 외부 API를 호출하지 않는 설명용 탐색 화면이며 제품 Frontend가 아니다.

## 책임과 데이터 접근

- Frontend는 Backend API만 호출한다. MCP, DB, 내부 토큰에 접근하지 않는다.
- Backend는 로그인과 기업 권한, 검토 이력 조회 및 삭제, 광고 버전 관리를 소유한다. 이력 CRUD를 MCP에 추가하지 않는다.
- Agent는 처리 순서, 도구 선택과 판단, HITL 중단과 재개를 소유한다. 업무 데이터는 MCP로 접근한다.
- Agent의 DB 직접 접근은 LangGraph 실행 상태와 HITL 체크포인트에만 허용한다. 현재 agent_user도 agent 스키마에만 권한이 있다.
- business-mcp는 structure_evidence, match_product_claims, verify_functional_approval, verify_patent와 match_patent_claims를 담당한다. 업무 DB와 Redis에 직접 접근하지 않는다.
- rag-mcp는 법령과 기준, 보조 사례를 검색하고 출처와 버전을 반환한다. 특허 검증을 중복 구현하지 않는다.
- Redis는 처리 중 원본 임시 보관, RabbitMQ는 다건 작업 참조 전달이다. 원본 바이트를 큐나 영구 체크포인트에 넣지 않는다.
- 공개 문서 색인은 사용자 요청과 별도 배치다. 문서와 임베딩을 Git에 저장하지 않는다.

## 단건과 다건 목표 흐름

| 단계 | 화면과 상태 | 서버 처리 | 실패 또는 예외 |
| --- | --- | --- | --- |
| 1. 입력 | 제품, 광고와 증빙 입력 | Backend가 형식, 크기와 파일 유효성 확인 | 암호, 손상과 형식 오류는 INPUT_REJECTED |
| 2. 접수 | 접수 완료 | 기업 권한과 잔액 확인, asset 해시와 광고 버전, Redis 임시 저장 | 권한 또는 잔액 문제는 실행 전 중단 |
| 3. 실행 | 처리 중 또는 다건 대기 | 단건 내부 HTTP, 다건 Agent API → RabbitMQ → Worker | 중복 command_id 통제, 실패 건만 재시도 |
| 4. 읽기 | 페이지별 추출과 확인 | Agent 광고 파싱과 Vision, Business MCP 증빙 구조화 | 불명확한 숫자와 각주는 WAITING_INPUT |
| 5. 주장 | 원문과 각주가 연결된 주장 | Agent가 블록과 시각 관찰을 구조화 | 추측한 수치와 불완전한 원문을 확정하지 않음 |
| 6. 검색 | 근거 검색 중 | rag-mcp가 rules와 cases를 분리 조회 | 빈 검색 결과와 검색 오류 구분, 목표 및 현재 계약은 03-contracts.md 참조 |
| 7. 대조 | 증빙과 공식 조회 확인 | business-mcp가 제품과 시험자료, 식약처와 특허 대조 | 자료 누락, 정상 미조회와 API 장애 구분 |
| 8. 초안 | 미승인 검토 초안 | Agent가 근거, 확인 사항과 리라이트 생성 | 미확인 항목을 남긴 부분 결과 표시 |
| 9. 승인 | 담당자 검토 | Backend 권한과 버전 재확인, Graph interrupt 재개 | 원본 필요 시 재업로드, 변경 광고는 새 버전 |
| 10. 보존 | 결과와 PDF, 과거 이력 | Backend 영구 저장, 원본 정리 확인과 별도 과금 이벤트 | 중복 이벤트 차감 방지, 실패 과금 정책은 미확정 |

원본 정리는 필요한 추출과 초안 저장 완료 시점에 수행하는 기준안을 제안한다.
마지막 단계는 정리 완료 확인과 잔여 파일 정리도 포함한다. 승인까지 원본을 유지할지는 팀 결정 사항이다.

~~~mermaid
sequenceDiagram
  participant U as Vue
  participant B as Spring Boot
  participant E as Agent API
  participant Q as RabbitMQ
  participant W as Agent Worker
  participant A as Agent Graph
  participant M as MCP 서버
  participant L as Vision / LLM API
  U->>B: 제품, 광고와 증빙 입력
  B->>B: 인증, 기업 권한, 잔액 확인
  alt 단건
    B->>E: 내부 HTTP로 즉시 실행
    E->>A: Graph 실행
  else 다건
    B->>B: 작업과 발행 대기 기록 저장
    B-->>U: 202와 접수 ID, 브로커 적재 완료와 구분
    B->>E: 인증된 건별 작업 실행 접수
    E->>Q: 작업 참조 발행과 confirm
    E-->>B: 접수와 발행 상태
    Q->>W: 건별 메시지 전달
    W->>A: 같은 Graph 실행
  end
  A->>A: 광고 PDF 파싱, 이미지와 페이지 준비
  Note over B,A: 파일 전달은 인증된 내부 입력 계약, Redis 직접 접근 아님
  A->>L: 필요한 이미지와 문자, 서버 API 키
  L-->>A: 문자와 시각 관찰, 주장 후보
  opt 판독 불명
    A-->>B: WAITING_INPUT과 확인할 영역
    B-->>U: 수정 또는 재업로드 요청
    U->>B: 원문 보정 또는 같은 파일 재업로드
    B->>A: 영향받는 입력 노드 재개
    A->>A: 판독 조건 재확인
  end
  loop 도구선택 한도 내 근거 보강
    A->>L: 문맥과 도구 목록, 다음 도구 선택
    L-->>A: tool call 또는 종료 제안
    A->>M: 필요한 기준 검색, 증빙 또는 공식 조회
    M-->>A: 출처, 버전, 조회 상태
  end
  A-->>B: 검토 초안과 승인 대기 이벤트
  B-->>U: SSE 진행과 미승인 결과
  opt 다건 승인 대기
    A-->>W: 초안과 checkpoint 저장 완료
    W->>Q: 해당 메시지 ACK
  end
  U->>B: 권한 있는 담당자의 승인 또는 수정 요청
  B->>B: 결정과 광고 버전 검증 및 저장
  alt 다건 재개
    B->>E: 저장된 결정의 재개 명령
    E->>Q: 재개 작업 발행
    Q->>W: 재개 작업 전달
    W->>A: 해당 버전과 checkpoint 재개
  else 단건 재개
    B->>E: 저장된 결정의 재개 명령
    E->>A: 해당 버전과 checkpoint 재개
  end
  A-->>B: 리포트 데이터와 처리 완료
  B->>B: 결과 저장, 과금 이벤트 전달
  opt 다건 실행
    A-->>W: 저장된 실행 상태 확인
    W->>Q: 수동 ack
  end
  B-->>U: 결과와 이력
~~~

이 시퀀스는 목표 설계다. 현재 작업 엔드포인트는 501이며 작업 실행을 흉내 내지 않는다.
단건 HTTP 응답 종료와 SSE 및 HITL 대기 간 수명 정책은 Agent와 Backend 담당자가 합의한다.
위 ack는 전달 책임의 종료를 뜻하며 HITL 최종 승인을 의미하지 않는다. 대기 상태를 영구 저장한 시점에도 ack할 수 있고 승인 후 재개는 별도 명령으로 전달한다.
HITL 대기 때문에 브라우저 HTTP 요청이나 Worker 한 개를 계속 점유하지 않는다.
대기 상태를 저장하고 요청을 종료한 뒤 승인 명령으로 재개한다. 다건은 시퀀스 이미지의 재개 메시지 발행을 따르고 메시지 스키마와 멱등성만 후속 계약으로 정한다.

## 리포트와 이력

Agent의 마지막 노드는 주장, 인용 근거, 증빙 대조와 사람의 결정을 보고서 데이터로 묶는다.
Backend는 해당 광고 버전과 근거 버전을 고정해 영구 저장하고 다운로드 및 이력 API를 제공한다.
PDF 출력은 노션의 WeasyPrint 후보를 사용하는 Python 렌더 adapter를 제안한다.
이 adapter는 모델 추론과 분리된 템플릿 렌더링이며 아직 구현하거나 의존성을 설치하지 않았다.
Backend가 보관한 보고서 스냅샷을 받아 렌더하고 업무 DB를 직접 조회하지 않는 경계를 사용한다.
같은 데이터를 다시 내려받을 때 LLM 재판정이나 새 법령으로 근거 교체를 자동 수행하지 않는다.
렌더 adapter의 내부 엔드포인트와 실행 서비스 배치는 Agent 및 Backend 담당자가 계약 PR에서 확정한다.

## 통신과 비밀 정보

| 구간 | 방식 | 인증과 키 |
| --- | --- | --- |
| Vue → Backend | HTTPS REST와 SSE | 사용자 로그인과 기업 권한 |
| Backend → Agent API | 내부 HTTP | 내부 서비스 인증과 서버가 만든 실행 문맥 |
| Agent API → RabbitMQ → Worker | AMQP, 목표 경로 | 전용 계정과 vhost, 작업 참조와 멱등 키 |
| Agent → 두 MCP | MCP Streamable HTTP | 내부 서비스 인증, 필요한 기업 범위 |
| business-mcp → Backend | 내부 HTTP | asset과 task의 기업 소유 검증 |
| Agent → Vision과 LLM | 공급자 HTTPS API | Agent 서버의 API 키 |
| business-mcp → 식약처와 특허 | 공식 외부 API | 해당 서비스의 인증 정보 |
| rag-mcp → knowledge | PostgreSQL 질의 | 읽기 전용 계정 |
| Agent → agent 스키마 | PostgreSQL | 체크포인트 전용 계정 |

로컬 PDF 파서와 로컬 OCR에는 LLM API 키가 필요하지 않다.
MCP는 모델 공급자 호출 방식이나 OCR 엔진을 대신하지 않는다.

## 네트워크

개발 포트는 127.0.0.1에만 바인딩한다. Compose 내부에서는 서비스 이름을 사용한다.
정적 Bearer 토큰은 개발용 서비스 인증이며 사용자 인증이나 기업 권한을 대체하지 않는다.

## Agent와 MCP의 최신 경계

처리 단계 표는 사용자에게 보여줄 책임별 진행 설명이며 MCP의 고정 호출 순서가 아니다.
최신 Graph 초안은 입력 파싱 후 제한된 도구선택 loop에서 LLM이 필요한 검색과 대조를 선택한다.
미제출 증빙과 없는 특허번호에 대해서는 불필요한 호출을 생략하고, 개별 API 장애는 해당 결과만 확인 불가로 남긴다.
호출 횟수와 시간 및 비용 한도, 오류 분류와 근거 충분성 검증은 Agent 담당자가 구현한다.

| 목표 MCP | 최신 노션 도구 | 현재 실행 기반 |
| --- | --- | --- |
| RAG | search_regulations, search_precedents | search_rules, search_cases와 get_evidence v0.1 stub. 전환 계약 필요 |
| Business | structure_evidence, match_product_claims, verify_functional_approval, verify_patent, match_patent_claims | 환경 연결 도구만 구현. 업무 adapter 후속 |

상세 초안의 이름과 필드가 바뀌었다고 실행 도구의 이름만 즉시 바꾸지 않는다.
노션의 미확정 필드와 현재 코드의 차이는 docs/03-contracts.md에 남기고 소비자와 계약 PR을 만든다.
특허 getWordSearch는 현재 초안 기준 명칭, 초록과 IPC까지만 대조할 수 있으며 청구항 원문 확보는 후속이다.

RabbitMQ의 ack와 nack, DLX는 전달 처리 수단이다. 실제 업무 큐와 DLQ 선언, 재시도 상한 및 지연,
DB 반영과 ack 순서, 중복 실행 제어는 애플리케이션 구현이 필요하다.
이번 초기 세팅은 브로커를 실행하고 합성 큐에서 confirm, nack/requeue와 ack를 검사하는 범위다.
공식 참고: [RabbitMQ 설치](https://www.rabbitmq.com/docs/download),
[전달 확인과 발행 확인](https://www.rabbitmq.com/docs/confirms).

## 상세 명세 재점검 결과

- Agent API, Worker와 Graph는 Graph 초안의 단일 Agent 서비스 안의 역할이다. 그림의 칸이 새 배포 서비스 수를 뜻하지 않는다. 현재도 services/agent 하나이며 업무 Worker는 미구현이다.
- 시퀀스 이미지에서 다건 202는 작업과 발행 대기 기록 저장 뒤, RabbitMQ 발행 전에 반환한다. 브로커 장애 시에도 작업을 복구할 영구 기록이 필요하다.
- 승인 대기 저장 후 ACK하고, 결정이 들어오면 새 재개 메시지를 발행한다. 최종 보고서까지 한 메시지를 계속 점유하는 구조로 구현하지 않는다.
- 목표 공개 API 경로는 노션 API 등록부의 /api/v1이다. 별도 REST 초안의 /api와 다른 ID 표기는 docs/03-contracts.md의 불일치 목록에 남긴다.
- 흐름 이미지에는 수정안 승인 시 새 광고 버전을 만드는 분기가 있다. Graph의 반려와 수정요청 재실행 설명과 함께 버전 및 재과금 정책을 맞춰야 한다.
