# 화장품 광고 사전검토 개발 기반

팀이 기능을 나누어 개발하기 위한 Spring Boot, Agent, 두 MCP 서버, PostgreSQL 개발환경이다.
현재 범위는 실행환경, 서비스 연결, 공통 계약과 문서다. 실제 광고 판정과 검색은 미구현 상태를 반환한다.

제품 요구사항 원본은 [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)다.
2026-10-20에 기획서 본문과 연결된 REST, MCP, Graph 및 실행 시퀀스 초안을 확인했으며, 노션 미확정 항목과 구현 선택은 문서에서 구분한다.

## 시작

Docker Engine과 Compose를 실행한 뒤 이 저장소에서 진행한다.

~~~sh
python3 scripts/bootstrap.py
docker compose up --build --wait
~~~

프론트엔드까지 함께 실행하려면 두 저장소를 같은 상위 폴더 아래에 둔다.

~~~text
capstone/
  backend/
  frontend/
~~~

~~~sh
docker compose --profile web up --build --wait
~~~

화면은 http://localhost:5173 이다. 기본 Redis는 원본 파일용 비영구 캐시 준비다.
다건 처리용 RabbitMQ를 추가하려면 queue 프로필을 켠다. 작업 발행과 Worker는 아직 구현하지 않았다.
전체 환경은 docker compose --profile web --profile queue up --build --wait로 함께 확인한다.

## 공통 문서

| 문서 | 내용 |
| --- | --- |
| [프로젝트 맥락](docs/00-project-context.md) | B2B 목표, 구현 범위와 미구현 기능 |
| [아키텍처](docs/01-architecture.md) | 서비스 경계와 실제 연결 구조 |
| [개발환경](docs/02-development.md) | Docker, 담당별 로컬 실행, 검증, 오류 해결 |
| [공통 계약](docs/03-contracts.md) | HTTP, MCP, 오류, 예정된 작업과 SSE 규칙 |
| [데이터](docs/04-data.md) | PostgreSQL과 벡터, 파일 저장, DB 권한 |
| [협업](docs/05-collaboration.md) | 담당 경계, 브랜치, PR와 변경 규칙 |
| [RAG 시작점](docs/06-rag-start.md) | 수집, 색인, 검색, 출처와 평가 준비 |
| [검증 기록](docs/07-validation.md) | 확인한 내용과 실행 환경의 제한 |
| [프로젝트 메모리](docs/08-project-memory.md) | 최신 결정, 폐기한 가정, 다음 작업 |
| [입력 처리](docs/09-input-pipeline.md) | PDF와 이미지 분기, OCR와 Vision, 키, 원본 수명과 예외 |
| [담당별 개발 계획](docs/10-work-breakdown.md) | 작업 카드, 입출력, 코드 배치와 통합 순서 |

제품 요구사항은 노션, 실행 코드의 계약은 packages/shared와 contracts가 원본이다.
docs는 두 저장소의 공통 구현 안내다. 프론트엔드에 문서 내용을 복사하지 않는다.

## 서비스

| 경로 | 기술 | 개발 포트 |
| --- | --- | --- |
| services/api | Java 21, Spring Boot 3.5.16, Maven | 8080 |
| services/agent | Python 3.12, FastAPI | 8001 |
| services/business-mcp | FastMCP 4.1.1 | 8002 |
| services/rag-mcp | FastMCP 4.1.1 | 8003 |
| infra/postgres | PostgreSQL 17, pgvector 0.8.7 | 55432 |
| redis-cache | Redis 7.4, 원본 캐시 준비 | 56379 |
| rabbitmq | RabbitMQ 4.3.6, queue 프로필 | 5672, 관리 UI 15672 |
| packages/shared | Pydantic 공통 계약과 연결 도우미 | 실행 서비스 아님 |

PostgreSQL 안에 벡터를 저장하므로 별도 Vector DB 서비스는 없다.
각 팀원의 DB는 로컬에 따로 생성되며 GitHub에 데이터가 자동 공유되는 구조는 아니다.

## 구현 범위

- 실제 연결: Vue API 프록시, Spring Boot의 DB 확인, Agent의 인증된 MCP 연결과 도구 목록 조회.
- 기본 제공: 공개 문서용 DB 스키마, DB 역할 분리, 잠금 파일, 환경변수 생성, CI와 검증 스크립트.
- 미구현: 사용자 로그인과 기업별 권한, 광고 업로드, OCR, LLM 호출, 검색과 색인, 승인과 재개, SSE, 과금, 외부 공공 API.
- 별도 과금 서비스와 원장 DB는 목표 경계만 문서화했으며 현재 실행 서비스에는 포함하지 않는다.
- 검색 도구는 NOT_IMPLEMENTED, 작업 접수는 HTTP 501로 응답한다. 가짜 판정이나 근거를 반환하지 않는다.

개발용 정적 서비스 토큰은 사용자 인증이 아니다. 공개 배포 전에 사용자 인증과 기업별 권한을 구현해야 한다.
실제 문서, 모델 가중치, 원본 광고와 자격증명은 Git에 올리지 않는다.

## 팀원이 처음 읽을 순서

1. [프로젝트 맥락](docs/00-project-context.md)에서 노션 요구와 현재 구현 범위를 확인한다.
2. [아키텍처](docs/01-architecture.md)와 [입력 처리](docs/09-input-pipeline.md)에서 파일 입력부터 승인까지 경로를 확인한다.
3. [담당별 개발 계획](docs/10-work-breakdown.md)에서 작업과 선행 계약을 선택한다.
4. [공통 계약](docs/03-contracts.md)의 현재 계약과 제안 계약을 구분한다.
5. [Git 협업 가이드](docs/05-collaboration.md)를 따라 기능별 브랜치와 PR을 만든다.

chore/dev-foundation은 초기 환경 작업 이름이다. Vue, Java 업무 서버, Python Agent와 두 MCP의 기반을 각각 포함한다.
입력 처리 설계에서 제안한 PDF 렌더, OCR와 Vision 호출은 아직 구현되지 않았다.

## 초기 세팅의 범위

서비스 분할은 Vue, Spring Boot, Agent, Business MCP와 RAG MCP다. RabbitMQ는 다건 작업 전달,
Redis는 원본 임시 보관, PostgreSQL은 업무와 실행 상태, pgvector는 공개 근거 검색을 맡는다.
과금은 별도 후속 서비스다. RabbitMQ를 모든 API, MCP 호출이나 파일 저장에 사용하지 않는다.

노션의 7개 MCP 도구와 14개 API 등록 항목, 기존 v0.1 계약 및 문서 간 차이는 docs/03-contracts.md에 정리했다.
목표 HTTP 경로는 API 등록부의 /api/v1이며 기존 REST 초안과의 표기 차이도 남긴다.
Agent API와 Worker는 하나의 Agent 서비스 내부 역할이다. 광고 파싱은 Agent, 증빙 파싱은 Business MCP 책임이다.
업무 기능 구현 전에 담당자끼리 계약 PR로 맞춘다. Frontend의 드래그앤드롭과 시작 버튼은
frontend/README.md의 개발 목표만 추가했으며 이번에 화면 기능을 구현하지 않았다.
