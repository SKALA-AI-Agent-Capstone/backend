# 화장품 광고 사전검토 로컬 MVP

제공된 Vue 화면을 Spring Boot, FastAPI Agent와 Business MCP에 연결한 단건 MVP다.
PDF와 이미지 업로드, 독립적인 문구와 사진 분석 경로, 결과와 버전 저장, 담당자 승인 및 Graph 재개를 제공한다.
OpenAI와 공공 API adapter는 서버 설정으로 연결한다. 키가 없으면 미연결 상태를 표시한다.
RAG는 Handoff 기반의 로컬 PostgreSQL + pgvector 설계, 출처 목록과 원문 수집 및 기본 청킹 점검까지 반영했다. 실제 검색과 적법 판정은 아직 하지 않는다.

제품 요구사항 원본은 [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)다.
2026-10-20에 기획서 본문과 연결된 REST, MCP, Graph 및 실행 시퀀스 초안을 확인했으며, 노션 미확정 항목과 구현 선택은 문서에서 구분한다.

## main에서 시작

처음 받는 팀원은 두 저장소를 같은 상위 폴더에 clone한다. 아래 경로는 예시이며 기존 개인 폴더가 없어도 실행할 수 있다.

```sh
mkdir beauty-capstone
cd beauty-capstone
git clone --branch main https://github.com/SKALA-AI-Agent-Capstone/backend.git
git clone --branch main https://github.com/SKALA-AI-Agent-Capstone/frontend.git
cd backend
python3 scripts/bootstrap.py
docker compose --profile web up --build -d --wait --wait-timeout 240
```

Docker Engine과 Compose를 실행한 상태에서 사용한다. 화면은 http://localhost:5173 이다.
처음에는 기업 작업공간을 만들면 된다. 선택적으로 uv 환경에서 검증 관리자 계정도 생성할 수 있다.

```sh
uv sync --all-packages --frozen
uv run --all-packages --frozen python scripts/setup_demo.py
```

검증 이메일은 master@beauty-review.local이며 실행자별 비밀번호는 Git에서 제외된 data/demo-access.md에 저장된다.
공용 마스터 비밀번호를 소스에 넣지 않는다. 현재 Docker DB나 계정이 clone으로 복사되는 구조가 아니다.
원본 파일, DB 내용, 실제 API 키와 모델 가중치도 Git에 포함하지 않는다.

## 작업 전에 읽기

1. [최신 노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)와 담당 영역의 연결 문서를 읽는다.
2. DB를 바꾸면 [노션 ERD v2](https://app.notion.com/p/Data-ERD-App-State-Vector-DB-3ff616ab8fec81c7b57ac75358ced570)를 확인한다.
3. 이 저장소의 [프로젝트 메모리](docs/08-project-memory.md), [현재 아키텍처](docs/01-architecture.md), [현재와 목표 ERD의 대응](docs/04-data.md)을 읽는다.
4. [계약](docs/03-contracts.md)과 [담당별 다음 작업](docs/10-work-breakdown.md)을 확인하고 main에서 feat 브랜치를 만든다.

노션의 목표, 현재 코드와 미구현 기능을 구분한다. API 명세 확인을 정상 실응답 검증으로 표시하지 않는다.
RAG는 Handoff 기준의 빈 knowledge DB와 출처 목록이며, 로컬 수집 및 기본 청킹 점검 코드까지만 추가됐다.

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

## 현재 범위

- 기업 작업공간과 JWT 로그인, 세션 로그아웃, 서버의 기업 및 역할 권한 검사.
- 실제 PDF와 이미지 multipart 업로드, Redis 임시 보관과 처리 후 폐기.
- pdfplumber, pypdfium2, Pillow와 Tesseract 한국어 OCR. OpenAI 문구 예비검토와 사진 독립 검토.
- FastMCP 증빙 추출, 식약처와 KIPRIS 조회 adapter. 실제 승인키와 정상 응답 검증은 별도다.
- PostgreSQL 결과와 버전, LangGraph HITL checkpoint, 담당자 결정 저장과 재개, 이력 삭제.
- RAG 출처 9건과 API 4종 목록, knowledge 스키마와 vector(1024). 화장품법은 legalize-kr에서 조문 단위로 적재하고 나머지는 PDF로 적재(scripts/ingest_rag.py). rag-mcp의 search_regulations와 search_precedents는 키워드 검색으로 동작하며 임베딩은 선택. Agent는 아직 RAG를 호출하지 않는다.
- SSE, 다건 RabbitMQ Worker, 실제 과금, 증빙 의미 대조와 특허 청구항 대조는 후속.

공개 계약은 /api/v1이다. 이전 /api/reviews 501 경로와 v0.1 검색 stub은 호환 기반이며 새 업무 화면에서 호출하지 않는다.
실제 모델과 공공 API 설정, 시작 방법 및 검증용 주소는 [개발환경](docs/02-development.md)을 따른다.
배포 및 인수 기준은 main이다. PR의 원격 Checks와 병합 커밋으로 반영 상태를 확인한다.
실제 문서, 모델 가중치, 원본 광고와 자격증명은 Git에 올리지 않는다.

## 팀원이 처음 읽을 순서

1. [프로젝트 맥락](docs/00-project-context.md)에서 노션 요구와 현재 구현 범위를 확인한다.
2. [아키텍처](docs/01-architecture.md)와 [입력 처리](docs/09-input-pipeline.md)에서 파일 입력부터 승인까지 경로를 확인한다.
3. [담당별 개발 계획](docs/10-work-breakdown.md)에서 작업과 선행 계약을 선택한다.
4. [공통 계약](docs/03-contracts.md)의 현재 계약과 제안 계약을 구분한다.
5. [Git 협업 가이드](docs/05-collaboration.md)를 따라 기능별 브랜치와 PR을 만든다.

chore/dev-foundation은 초기 실행환경 브랜치다. 후속 기능은 최신 main에서 별도 feat 브랜치를 만들어 진행한다.
서비스별 다음 작업과 인터페이스 담당은 docs/10-work-breakdown.md를 따른다.
