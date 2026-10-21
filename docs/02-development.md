# 로컬 개발과 실행

현재 범위는 OpenAI 기준 단건 MVP와 로컬 PostgreSQL + pgvector다. 클라우드 가입이나 별도 Vector DB 서버는 필요 없다.
Frontend와 Backend는 같은 상위 폴더의 별도 Git 저장소다.

```text
capstone/
  backend/
  frontend/
```

## 전체 실행

Docker Engine과 Compose를 켠 뒤 backend에서 실행한다.

```sh
python3 scripts/bootstrap.py
docker compose config --quiet
docker compose --profile web up --build -d --wait --wait-timeout 240
```

bootstrap은 .env의 누락된 설정만 보충하고 기존 비밀번호를 바꾸지 않는다.
앱은 http://localhost:5173 이며 처음에는 기업 작업공간을 만든다. 공개 데모 계정이나 공용 비밀번호는 없다.
회사 관리자는 /api/v1/users/team으로 같은 기업의 author 또는 reviewer 계정을 만들 수 있다. 팀원 관리 UI는 후속이다.
팀원의 시작 기준은 두 저장소의 main이다. 기능 작업은 main에서 새 feat 브랜치를 만들어 진행한다.

## 키와 환경변수

키 값은 backend/.env 또는 각 서비스의 비밀 환경변수에만 넣는다. VITE_*에 넣지 않는다.

| 변수 | 소비 서비스 | 필요 조건 |
| --- | --- | --- |
| OPENAI_API_KEY | Agent | 실제 문구 모델과 사진 VLM 호출 |
| TEXT_MODEL, VISION_MODEL | Agent | 기본 gpt-5.4-mini, 사용 계정의 모델 권한 확인 필요 |
| MODEL_MAX_RETRIES, FALLBACK_TEXT_MODEL, FALLBACK_VISION_MODEL | Agent | 선택. 429, 5xx, 연결 오류는 기본 2회 재시도하고, 대체 모델을 적으면 그 뒤 한 번 더 시도한다. 비우면 해당 경로가 partial로 남는다 |
| MFDS_SERVICE_KEY, MFDS_REPORT_URL | Business MCP | 공식 getRptPrdlstInq01 기본 주소 제공, 키와 정상 실응답 검증 필요 |
| KIPRIS_SERVICE_KEY | Business MCP | KIPRIS Plus 해당 오퍼레이션 이용 권한 |
| KIPRIS_WORD_SEARCH_URL | Business MCP | 공식 getWordSearch 경로 기본값, 청구항 원문 경로가 아님 |
| JWT_SECRET | Backend | bootstrap이 생성하는 로컬 서명 키 |
| JWT_ACCESS_TTL, JWT_REFRESH_TTL | Backend | 선택. ISO-8601 기간, 기본 PT15M과 P14D |
| JWT_REFRESH_REUSE_GRACE, JWT_REFRESH_COOKIE_SECURE | Backend | 선택. 기본 PT30S와 true. Safari로 http 로컬 접속 시에만 false |
| INTERNAL_SERVICE_TOKEN | 서버 간 | bootstrap이 생성하는 내부 인증 토큰 |
| DB 및 REDIS 비밀번호 | 서비스별 | bootstrap이 생성, 프론트에 전달하지 않음 |
| LAW_OC | 향후 RAG 수집 배치 | 법제처 XML 수집을 선택하는 경우 |
| LANGSMITH_TRACING, LANGSMITH_API_KEY, LANGSMITH_PROJECT, LANGSMITH_ENDPOINT | Agent | 선택. 둘 다 있을 때만 LangSmith로 노드, 도구, 모델 호출 trace를 보낸다. 실행마다 review_id 메타데이터와 result.traceId가 남는다. trace는 LANGSMITH_PROJECT(기본 beauty-capstone) 프로젝트에 쌓인다. LangSmith agent 주소(LANGSMITH_AGENT_ID, LANGSMITH_AGENT_ENVIRONMENT)는 프로젝트와 함께 쓸 수 없어 컨테이너에 넘기지 않는다 |
| LANGSMITH_HIDE_INPUTS, LANGSMITH_HIDE_OUTPUTS | Agent | 기본 true. trace는 외부로 나가므로 광고 문구, 증빙과 이미지를 숨기고 경로, 지연, 토큰과 오류만 남긴다. 합성 자료로 로컬 디버깅할 때만 false |

PDF 문자 추출, Tesseract 한국어 OCR와 Pillow 타일 분할에는 외부 API 키가 없다.
키가 없어도 로그인, 업로드, 로컬 추출, 저장과 승인 연결을 확인할 수 있다. AI 분석 성공을 흉내 내지는 않는다.
식약처 기본 주소와 컬럼은 공식 포털의 Swagger에서 확인했다. 키가 없으면 API_NOT_CONFIGURED로 표시한다.
KIPRIS adapter도 승인키를 이용한 실제 정상 응답 검증은 남아 있다.

.env 수정 후 실행 중인 서비스에 설정을 적용한다.

```sh
docker compose up -d agent business-mcp
```

## 주소와 데이터

| 서비스 | 기본 호스트 주소 | 용도 |
| --- | --- | --- |
| Vue | http://localhost:5173 | 사용자 화면 |
| Spring Boot | http://127.0.0.1:8080 | /api/v1, 업무 DB와 원본 관리 |
| Agent | http://127.0.0.1:8001 | 내부 실행, HITL 재개 |
| Business MCP | http://127.0.0.1:8002/mcp | 증빙과 외부 조회 |
| RAG MCP | http://127.0.0.1:8003/mcp | legalize-kr 코퍼스는 pgvector 검색 연결, 나머지 출처는 적재 중 |
| PostgreSQL | 127.0.0.1:55432 | app, agent와 knowledge |
| Redis | 127.0.0.1:56379 | 원본 임시 캐시 |

DB는 Docker named volume에 남는다. 종료는 `docker compose down`이며 `down -v`를 일반 절차로 사용하지 않는다.
기존 DB 변경은 Flyway 신규 migration으로 적용한다. knowledge 초기 SQL은 빈 개발 DB에만 실행된다.
기존 볼륨에서 .env 비밀번호만 바꾸면 DB 역할 비밀번호가 자동 변경되지 않는다.

## 역할별 실행

모든 담당자가 매번 전체 서비스를 재개발할 필요는 없다.

| 담당 | 보통 켜는 범위 | 수정 위치 |
| --- | --- | --- |
| Frontend | Backend 전체 Docker와 호스트 Vite | src/pages, src/shared/api, src/shared/stores |
| Backend | PostgreSQL, Redis와 Agent Docker | services/api |
| Agent | DB, Backend와 두 MCP, 호스트 Agent | services/agent |
| Business MCP | Backend와 의존 서비스, 호스트 MCP | services/business-mcp |
| RAG | PostgreSQL과 호스트 rag-mcp | services/rag-mcp, 공개 수집 코드 |

```sh
# 데이터만 실행
docker compose up -d --wait postgres redis-cache
# Python 공통 설치
uv sync --all-packages --frozen
# 각 명령은 별도 터미널에서, 같은 포트의 컨테이너와 중복 실행하지 않음
uv run --all-packages --frozen --env-file .env capstone-agent
uv run --all-packages --frozen --env-file .env business-mcp
uv run --all-packages --frozen --env-file .env rag-mcp
# Java 21과 Maven 환경에서
python3 scripts/run_api.py
```

호스트 서비스와 컨테이너의 DB 주소는 다르다. bootstrap의 호스트 DSN은 127.0.0.1이고 Compose 내부 DSN은 postgres:5432다.
Agent 호스트 실행에도 BACKEND_BASE_URL과 모델 설정이 필요하다. .env의 호스트용 값은 .env.example을 따른다.
Frontend 호스트 개발은 `cd ../frontend`, `npm ci`, `npm run dev`다. 기본 API 프록시는 8080이며 API_PROXY_TARGET으로 변경한다.
컨테이너의 웹 코드는 이미지에 포함되므로 수정 후 `docker compose --profile web up --build -d web`로 갱신한다.

## 검증

```sh
uv run --all-packages --frozen pytest
uv run --all-packages --frozen python scripts/export_contracts.py --check
mvn -f services/api/pom.xml verify
# 실제 실행 중인 서버에서, 합성 데이터만 사용
MVP_API_URL=http://127.0.0.1:8080 uv run --all-packages --frozen python scripts/mvp_smoke.py
# Frontend 저장소에서
npm run build
```

mvp_smoke는 격리된 개발 DB에서 실행한다. 합성 기업과 사용자를 만들고 PNG/PDF를 업로드한 뒤 검토 이력을 삭제한다.
테스트 계정은 격리된 DB에 남으며 실제 고객 자료나 외부 유료 모델을 사용하지 않는 설정에서 검증한다.
scripts/smoke.py는 기반 연결, 인증, 빈 RAG와 DB 권한 검사다. Agent의 신규 필수 입력 422에 맞춰 갱신했다. 실제 단건 기능 근거는 mvp_smoke.py이며 두 검사를 CI에 포함했다.

2026-10-20 검증용 별도 프로젝트는 beauty-mvp-verify다. 이 프로젝트의 웹은 http://127.0.0.1:15173,
API는 18080, Agent 18001, MCP 18002와 18003, PostgreSQL 55439, Redis 56389를 사용한다.
이는 기존 사용자 DB와 포트를 피하기 위한 검증 설정이며 기본 포트를 변경한 요구사항은 아니다.
검증에서는 OPENAI_API_KEY, MFDS_SERVICE_KEY와 KIPRIS_SERVICE_KEY를 비워 두었다.

## 선택 환경과 현재 한계

RabbitMQ는 `docker compose --profile queue up -d --wait rabbitmq`로 켤 수 있다.
업무 큐와 Worker, DLQ, SSE와 과금은 후속이며 기본 단건 흐름에 필요하지 않다.
현재 실행 중인 API를 재시작하면 메모리 실행 풀의 대기 작업은 자동 복구되지 않는다.
원본 만료나 분석 실패 시 새 파일을 제출한다. 결과 항목별 재시도 버튼을 가짜 성공으로 연결하지 않는다.

## 검증 관리자와 실제 광고 파일 점검

```sh
python3 scripts/bootstrap.py
uv run --all-packages --frozen python scripts/setup_demo.py
uv run --all-packages --frozen python scripts/acceptance.py --input /path/to/ad.png /path/to/ad.pdf --include-pdf
uv run --all-packages --frozen python scripts/audit_sources.py --download --probe-without-key
```

검증용 이메일 기본값은 master@beauty-review.local이다. DEV_ADMIN_PASSWORD는 실행자별로 자동 생성하고
로그인 정보는 Git에서 제외된 data/demo-access.md에 권한 0600으로 저장한다. 프로그램이 계정과 API 키 값을 로그에 출력하지 않는다.
이 계정은 자기 검증 작업공간의 관리자이며 다른 기업 자료에 대한 우회 권한이 없다.
재실행 시 기존 계정으로 로그인하며 다른 비밀번호로 기존 계정을 임의 초기화하지 않는다.

API가 기본 8080이 아니면 --api-url을 지정한다. 별도 자격증명 파일은 --env-file /path/to/local.env로 전달할 수 있다.
키 값은 프로그램이 직접 읽고 HTTP 요청에 사용한다. 채팅이나 커밋에는 넣지 않는다.
acceptance 보고서는 reports/acceptance.json이며 실제 입력, 계정 비밀번호와 보고서 원문은 Git에 포함하지 않는다.
광고 파일은 파일당 25 MiB, 전체 제출 75 MiB다. 픽셀, 페이지 및 타일 한도는 별도로 유지한다.
