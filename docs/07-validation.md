# 검증 기록

검증일: 2026-10-19. 최신 노션을 반영한 개발 기반을 로컬에서 실제 실행했다.

| 항목 | 결과 |
| --- | --- |
| Python pytest | 10개 통과 |
| Pydantic 계약과 JSON Schema 비교 | 일치 |
| Spring Boot Maven verify | 테스트 2개와 패키징 통과 |
| Frontend 타입 검사와 Vite 빌드 | 통과 |
| Compose 설정과 이미지 빌드 | 통과 |
| PostgreSQL, API, Agent, 두 MCP, Vue, 두 Redis | 8개 컨테이너 기동 |
| HTTP와 MCP smoke | 통과 |
| localhost 개발 화면 | 연결 상태 6개와 search_cases 도구 등록 확인 |

## 실제로 확인한 경계

- Vue 프록시를 통한 Backend 요청, Backend의 DB 및 Agent 상태 집계.
- Spring 내부 API와 MCP의 토큰 없는 요청 거부, 인증된 business-mcp의 Spring 호출.
- Agent 실행 API의 501 응답, 두 RAG 검색 도구의 NOT_IMPLEMENTED와 빈 근거.
- pgvector 확장, vector(1024) 열, 합성 벡터의 거리 연산.
- rag_reader의 knowledge SELECT 허용, INSERT와 app 스키마 접근 거부.
- rules와 cases의 컬렉션 필드, 공개 출처 검증 필드가 있는 빈 스키마.
- Redis 캐시의 snapshot 및 AOF 비활성, 별도 큐 Redis의 AOF 활성.
- 커밋 후보 파일에 로컬 생성 비밀번호와 토큰, 중앙점이 없는지 확인.

처음 PostgreSQL 초기화 스크립트를 호스트에서 마운트할 때 발생한 권한 문제는
초기화 파일을 이미지에 포함하는 방식으로 수정하고 새 빈 테스트 DB로 재검증했다.
제한된 JVM 실행환경에서 Mockito 에이전트 연결이 실패해 일반 로컬 JVM 권한으로 재실행했고 통과했다.

## 확인하지 않은 것

- 실제 광고 판정, OCR와 LLM 추론, 문서 수집과 검색 정확도, 임베딩 생성.
- 실제 큐 발행, Worker, 재시도와 메시지 전달 보장.
- 원본 업로드와 삭제, 사용자 로그인, 기업별 데이터 권한, HITL와 SSE.
- 식약처와 KIPRIS API 실응답, 원본 특허 확보와 대조.
- 별도 과금 서비스와 DB, 잔액과 차감, 원장과 충전 목업.
- 운영 배포, 부하와 보안 검증.

GitHub Actions는 같은 검증을 수행하도록 구성했다. 원격 CI 결과는 각 PR의 Checks가 기준이다.
로컬 검증 통과만으로 원격 CI 성공이라고 표시하지 않는다.
검증용 Compose 프로젝트는 종료하며 DB 볼륨은 삭제하지 않는다. 개발 실행 방법은 02-development.md를 따른다.

## 입력과 서비스 흐름 설명 화면

2026-10-19에 실제 제품 화면과 별개인 설명용 인터랙션을 검증했다.
설계 분기를 합성 데이터로 탐색하며 파일 업로드, OCR와 유료 API 호출은 실행하지 않는다.
아래 입력 6종과 직접 카피 경로는 초기 시안의 검증 이력이다. 현재 범위는 PDF와 이미지 두 종류이며,
직접 카피 입력은 제외한다. PDF 세부 유형과 긴 이미지는 서버 내부 처리 분기다.

- 입력 6종: 직접 카피, PNG/JPEG, 텍스트 PDF, 스캔 PDF, 혼합 PDF와 긴 상세페이지.
- 단건 내부 HTTP와 다건 202, Queue 및 Worker 경로 변경.
- 예외 11종: 손상 파일, 잔액 부족, 원본 캐시 소실, 판독 불명, 모델 장애, 검색 없음, 증빙 누락, 공공 API 미조회, 공공 API 장애, 승인 시 원본 부재와 광고 버전 변경.
- 진행 불가 상황에서 다음 단계가 비활성화되고 부분 결과 상황은 이후 검토 단계로 이어짐을 확인.
- 로컬 OCR 선택과 직접 카피의 Vision 생략, 키를 보관할 서버의 표시 확인.
- 320px와 360px 폭에서 가로 넘침 없이 컨트롤과 그래프가 배치됨을 확인.
- JavaScript 구문 검사와 외부 API 호출 코드 부재 확인.

docs/09-input-pipeline.md의 prepare_input, SourceBlock과 오류 코드는 제안 계약이며 코드에 구현하지 않았다.
이번 보완은 Markdown과 PR 템플릿 변경이다. 기존 서버 기능 테스트를 신규 OCR 기능 검증으로 해석하지 않는다.

## 2026-10-20 재점검

| 항목 | 이번 결과 |
| --- | --- |
| 원격 Backend PR #1 | OPEN Draft, Head 2e25626, python과 java 및 integration SUCCESS |
| 원격 Frontend PR #1 | OPEN Draft, Head 27ba483, build SUCCESS |
| Python pytest | 10개 통과 |
| 계약 JSON Schema | 일치 |
| Frontend 타입 검사와 빌드 | 통과 |
| Spring Boot Maven verify | 테스트와 패키징 통과 |
| Compose config --quiet | 통과 |

원격 CI는 2026-10-19 실행 결과를 2026-10-20에 확인했다. 이번에 원격 workflow를 새로 실행하지 않았다.
Java 오프라인 실행은 로컬 Maven 캐시 부족으로 중단됐고, 일반 모드에서 의존성 확보 후 verify를 통과했다.
이번에는 전체 컨테이너 기동과 HTTP/MCP smoke를 다시 실행하지 않았다. 해당 통합 검증은 기존 기록과 원격 CI를 근거로 한다.
업무용 HTTP 명세와 Frontend Mock, 로그인과 업무 화면, 실제 Graph와 검색은 여전히 미구현이다.

## 2026-10-20 최신 노션 전체 구조 반영과 재실행

기획서 본문과 REST, MCP, Graph, RabbitMQ 시퀀스 초안을 브라우저에서 확인했다.
노션 API 연동은 해당 페이지를 찾지 못했으며 브라우저 공개 본문을 근거로 사용했다. 노션 원문은 수정하지 않았다.
앞의 두 Redis 검증은 2026-10-19의 이력이다. 현재 큐는 RabbitMQ이고 Redis는 원본 캐시 하나다.

| 검증 | 이번 결과 |
| --- | --- |
| Python pytest | 12개 통과, 기존 자격증명 보존과 bootstrap 재실행 및 신규 파일 권한 포함 |
| 계약 JSON Schema | 기존 v0.1과 일치, 최신 노션 초안으로 런타임 계약을 임의 변경하지 않음 |
| Java Maven verify | 테스트 2개와 패키징 통과 |
| Frontend 타입 검사와 빌드 | 통과, 변경 파일은 README.md뿐 |
| Compose queue 프로필 설정 | 통과 |
| 별도 검증 프로젝트 전체 기동 | Vue, API, Agent, 두 MCP, PostgreSQL, Redis와 RabbitMQ 8개 실행 |
| Vue /api 프록시 | Backend, Agent와 두 MCP 연결 상태 모두 정상 |
| HTTP와 MCP smoke | 내부 인증 거부, 두 MCP 실제 호출과 미구현 상태 확인 |
| pgvector | 1024차원 연산, 스키마와 읽기 전용 권한 확인 |
| Redis 캐시 | snapshot과 appendonly 비활성 확인 |
| RabbitMQ AMQP | 합성 임시 큐에서 publisher confirm, 수신, nack/requeue, 재전달과 manual ack 확인 |
| Markdown | 로컬 링크, fence, 중앙점 부재와 git diff --check 확인 |

검증용 프로젝트는 beauty-capstone-audit-20261020이다. 테스트 메시지는 합성 데이터이며
연결 종료 시 임시 큐가 사라진다. 실제 광고 파일이나 공개 근거 문서를 업로드 또는 적재하지 않았다.
검증 후 이 프로젝트의 컨테이너만 종료하며 기존 사용자 서비스와 DB 볼륨을 삭제하지 않는다.

업무 publisher와 Worker, durable 업무 큐 및 DLQ, 제한 재시도와 멱등 상태는 미구현이다.
브로커의 confirm과 ack 검증을 업무 배치와 장애 복구 구현 완료로 해석하지 않는다.
업로드 UI, OCR와 Vision, Graph와 HITL, SSE와 공공 API 및 실제 검색, 과금도 후속이다.
원격 CI 결과는 이 변경을 push한 뒤 PR Checks에서 별도로 확인한다.

## 2026-10-20 상세 명세 전체 재점검

기획서와 MCP 문서 두 개, REST 초안, API 등록부, Graph의 전체 본문 및 하단,
RabbitMQ 시퀀스와 흐름 이미지 두 개를 읽고 설계 MD를 보완했다.
API 등록부 14개 항목의 /api/v1 경로와 기존 REST 초안의 /api 불일치,
7개 MCP 도구의 정확한 인자와 반환 필드, 단일 Agent 서비스와 승인 대기 저장 후 ACK 및 재개 경로를 반영했다.

변경 범위는 기존 Markdown 문서와 AGENTS.md뿐이다. 런타임 소스, 생성 계약 Schema, 의존성과 Compose를 바꾸지 않았다.
현재 기반 v0.1과 목표 명세를 구분하고, 노션 문서의 불일치를 모두 해결했다고 주장하지 않는다.
이번 확인을 외부 API 실제 호출, OCR나 검색 구현 또는 새 전체 컨테이너 통합 검증으로 보고하지 않는다.
검증은 Markdown 로컬 링크와 fence 및 표 구조, 중복 제목, 중앙점 부재와 git diff --check를 대상으로 한다.

## 2026-10-20 실제 단건 MVP 검증

앞의 초기 기반과 문서 재점검 이력 이후, 사용자 요청으로 실제 단건 기능을 추가했다.
검증 프로젝트는 beauty-mvp-verify이며 사용자 DB와 분리된 로컬 Docker 볼륨을 사용했다.
외부 OpenAI, MFDS와 KIPRIS 키는 비워 두고 합성 자료만 사용했다.

| 검증 | 실제 결과 |
| --- | --- |
| Python pytest | 19개 통과, 긴 이미지 구간과 중복 제거, 두 분석 경로, 가짜 근거 차단 포함 |
| Pydantic과 JSON Schema | 현재 4개 MVP 계약 및 기존 계약 export 검사 통과 |
| Spring Boot Maven verify | Security 경계 테스트 3개와 패키징 통과 |
| Frontend vue-tsc와 Vite build | 제공 Vue 화면 통합 후 통과 |
| Docker | Vue, API, Agent, 두 MCP, PostgreSQL과 Redis 7개 기동 |
| 서버 JWT와 세션 | 합성 작업공간과 팀 역할, 로그아웃 후 토큰 거부 |
| 실제 multipart | 합성 PNG와 native PDF, 증빙 PDF 바이트 업로드 성공 |
| 실제 파서 | PDF 문자 추출과 컨테이너 Tesseract OCR, 독립 결과 필드 확인 |
| 인증된 MCP | 실행별 범위의 structure_evidence가 Backend에서 실제 증빙을 읽고 추출 |
| 권한 | 다른 기업 review 조회 404, 작성자 승인 403, 빈 검토 의견 400 |
| HITL | DB checkpoint 저장, 검토자 결정 저장 후 PostgreSQL 상태 복원과 재개 completed |
| 버전 | 새 파일로 v2 생성, 독립 승인 대기, 이전 버전 결정 차단 |
| 이력 삭제 | app 이력과 모든 버전의 Graph checkpoint 삭제 확인 |
| Handoff | 출처 후보 9개 API 반환, 실제 문서와 chunks는 각각 0건 |
| pgvector | 0.8.7 확장, rag_reader의 SELECT 허용, INSERT와 app 접근 거부 |

재현 스크립트는 scripts/mvp_smoke.py다. 첫 통합 검증에서 Spring 트랜잭션 proxy의 직접 필드 접근 때문에
업로드가 실패한 문제를 proxy-safe 메서드 접근으로 수정하고 같은 HTTP 검증을 통과했다.
모델 테스트의 합성 Provider는 두 경로의 입출력과 원문 포함 검증을 확인하며 실제 모델 정확도를 의미하지 않는다.

실행하지 않은 범위는 실제 OpenAI 유료 추론, 식약처와 KIPRIS 승인키 정상 응답, 고객 광고 정확도 평가,
공개 법령 수집 및 임베딩과 RAG 검색, 다건 Worker와 SSE, 증빙 의미 대조와 특허 청구항 대조, 과금과 운영 부하다.
모델 키 미설정은 not_configured로 표시한다. RAG 상태는 아래 2026-10-20 기록을 따른다.

## 2026-10-20 RAG와 Agent 연결 e2e (feat/rag-agent-connect)

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 단위 테스트 | uv run --all-packages --frozen pytest | 44 passed |
| Java | mvn -f services/api/pom.xml verify | 18 tests, 0 failures |
| Frontend | npm run build (vue-tsc 포함) | 성공 |
| 실제 업로드 | 합성 한국어 광고 PNG(주장 5개)를 /api/v1/reviews로 업로드, OpenAI 키 설정 | done, retrievalStatus=connected |
| OCR | Tesseract가 6줄 중 3줄을 높은 신뢰도로 읽고 3줄을 누락 | VLM OCR 보충 후 5개 주장 모두 추출 |
| 근거 | 문구별 basis | 5건 모두 화장품법 제13조, 수치 주장 2건은 제14조, 2건은 시행규칙 제23조 추가 |
| 화면 | 로컬 테스트 계정으로 결과, 출처, 리포트 화면 확인 | 상태별 문구, 조문 후보, 적용 기준 버전 표시 |

남은 한계: 검색은 키워드 방식이고 PDF 출처 8건은 미적재다. 리라이트 품질(예: "식약처 기준에 맞춘 화장품")은 모델 재검증에 의존한다.
로컬 검증과 GitHub CI는 별개이며 이번 기능의 원격 push나 CI 성공을 이 기록으로 주장하지 않는다.

브라우저에서는 합성 기업 작업공간 생성, 실제 PNG와 PDF 선택 및 제출, 저장된 추출 문구와
두 분석 경로의 not_configured, 공공 API 미설정과 RAG 미연결 표시를 확인했다.
제공 Desktop 프론트 폴더에도 직접 반영한 뒤 npm ci와 타입 검사 및 빌드를 통과했다.
CI 정의는 기존 연결 검사와 신규 mvp_smoke를 함께 실행하도록 갱신했으며 원격 실행은 아직 하지 않았다.

마지막 기반 연결 검사 scripts/smoke.py도 새 Agent 입력 계약과 실제 로컬 포트로 실행해 통과했다.
이번에는 RabbitMQ 선택 프로필을 다시 켜지 않았으므로 기존 AMQP 검증 이력과 신규 단건 검증을 구분한다.


## 사용자 원본과 공식 출처의 실제 점검

2026-10-20 제공한 원본 이미지 3장과 합성 native PDF를 실제 HTTP로 제출했다. 기존 파일당 10 MiB 제한에서
첫 두 원본을 받을 수 없는 문제를 파일당 25 MiB와 전체 75 MiB로 수정했다. 픽셀 및 페이지와 타일 제한은 유지했다.

| 입력 | 원본 크기 | 구간 | OCR 또는 native 블록 | 실제 상태 |
| --- | --- | --- | --- | --- |
| A1, 긴 상세페이지 | 848 x 19,799, 약 20.1 MiB | 15 | 87 | 접수 202, 추출 및 결과 저장 완료 |
| A2, 긴 상세페이지 | 537 x 21,482, 약 13.7 MiB | 16 | 157 | 접수 202, 추출 및 결과 저장 완료 |
| A3, 모공 비교 광고 | 1,176 x 1,180 | 1 | 7 | 접수 202, 추출 및 결과 저장 완료 |
| A4, 합성 native PDF | 1페이지 | 1 | 2 | 접수 202, 추출 및 결과 저장 완료 |

원본 범위에 빈틈이 없고 인접 타일이 겹치는지 확인했다. 이 수치는 OCR 정확도나 사진 판정 점수가 아니다.
실제 OpenAI 키가 없는 실행에서는 text와 visual 모두 not_configured다. API로 사진 과장을 탐지했다고 보고하지 않는다.
프롬프트는 비교 관찰과 한계를 기록하고, 프라이머의 일시적 메이크업 커버를 지속적인 피부 구조 개선과 구분하도록 보강했다.
모델이 실제 보정 여부, 효능이나 법 위반을 확정하도록 만들지 않았다.

### 문서 9건과 API 4종

노션에는 근거 문서 9건과 API 4종이 별도 목록이다. API를 포함해 총 9개가 모두 통과했다고 합쳐 표시하지 않는다.
아래는 같은 검사 시점의 결과이며 전체 응답과 원문은 Git에서 제외된 reports 및 data/source-audit에 있다.

| ID | 대상 | 확인 결과 |
| --- | --- | --- |
| S01 | 화장품 표시, 광고 관리 지침 | PDF 17페이지, 기본 청크 19개 |
| S02 | 화장품 광고자문 기준 및 해설서 (2025개정) | PDF 35페이지, 기본 청크 51개 |
| S03 | 화장품 표시, 광고 실증을 위한 시험방법 가이드라인 | PDF 103페이지, 기본 청크 126개 |
| S04 | 2026년 화장품 표시광고 지침 및 위반사례 등 안내 | PDF 28페이지, 기본 청크 28개 |
| S05 | 화장품법 제13조, 제14조 | 페이지 확인, 원문 자동 확보는 LAW_OC 또는 수동 확인 필요 |
| S06 | 화장품법 시행규칙 별표 5 | PDF 2페이지, 기본 청크 3개 |
| S07 | 화장품 표시, 광고 실증에 관한 규정 | 페이지 확인, 원문 자동 확보는 LAW_OC 또는 수동 확인 필요 |
| S08 | 기능성화장품 심사에 관한 규정 | PDF 55페이지, 기본 청크 67개 |
| S09 | 표시, 광고의 공정화에 관한 법률 제3조 | 페이지 확인, 원문 자동 확보는 LAW_OC 또는 수동 확인 필요 |
| API01 | 식약처 기능성화장품 보고품목정보 | 공식 명세와 요청 주소 확인, 승인키 실응답 미검증, 키 없는 요청 HTTP 401 |
| API02 | KIPRIS Plus 특허 및 실용 공개, 등록공보 | 공식 명세와 요청 주소 확인, 승인키 실응답 미검증, 키 없는 요청 HTTP 200 |
| API03 | 식약처 화장품 GMP 적합 업체현황 | 공식 명세와 요청 주소 확인, 승인키 실응답 미검증, 키 없는 요청 HTTP 401 |
| API04 | 식약처 화장품 규제정보 | 공식 명세와 요청 주소 확인, 승인키 실응답 미검증, 키 없는 요청 HTTP 401 |

PDF 6건, 총 240페이지에서 기본 청크 294개를 만들고 제목 및 원문 구간 보존을 확인했다.
이는 데이터 수집과 기본 청킹 미리보기다. 표 및 예외의 의미 복원과 출처 현행성 검증, 임베딩과 knowledge DB 적재 및 실제 검색은 남아 있다.
S04는 규정과 사례의 분리 검증 전이므로 고신뢰 rules로 그대로 적재하지 않는다.
API의 HTTP 200도 정상 데이터 응답을 뜻하지 않는다. 키가 없는 식약처 요청은 401이고 KIPRIS의 200은 인증된 정상 조회 검증으로 취급하지 않는다.

공식 포털의 Swagger 3개를 contracts/external에 저장했고 취하 품목 및 페이지 조회 한도, 미확인 효능 범위를 검증하는 테스트를 추가했다.
현재 Python 테스트는 23개다. 실키가 준비되면 scripts/acceptance.py와 scripts/audit_sources.py를 다시 실행해 모델 및 API 상태를 갱신한다.

main 인수는 원격 PR Checks와 main 병합 기록으로 확인한다. README에는 팀원의 clone, 로컬 설정 생성과 실행,
작업 전 노션 및 ERD 확인 순서가 있다. 사용자 원본, 실제 키, 검증 관리자 비밀번호와 DB 내용은 Git에 포함하지 않는다.


## 2026-10-20 문구별 위반 저장 전환 검증 (feat/violation-tables)

- `mvn test`(services/api): 12개 통과. 새 ViolationFlowTest 9개는 Testcontainers의 postgres:17-alpine에 Flyway V1~V5를 적용해 실행한다.
  결과 저장과 finding 연결, 위반 0건 자동 승인 조건, 문구별 결정과 수동 문구 미저장, 미결정 승인 409, changes_requested 매핑,
  사람 다시쓰기 한도 409, 모델 미설정 503, 기업 범위 404와 권한, 재검토 초기화, V5 backfill을 확인한다. Agent와 Redis는 대체했다. Docker가 없으면 이 클래스는 건너뛴다.
- Python `pytest`: 26개 통과(다시쓰기 endpoint 2개와 프롬프트 1개 추가). `scripts/export_contracts.py --check` 통과.
- docker compose로 api와 agent를 다시 빌드했다. 로컬 DB에 V5가 적용되어 기존 결과가 violations로 옮겨졌다.
  처음 작성한 V5는 app_user에 임시 테이블 권한이 없어 실패하고 롤백되었으며, 한 문장의 CTE로 바꾼 뒤 적용되었다.
- 실제 compose API에서 합성 광고 이미지 1건으로 확인: 실제 모델 분석 done, violations 3건과 finding 연결,
  다른 기업 계정의 조회와 문구 결정, 다시쓰기, 전체 결정 모두 404, rewritten_manual 501, 사람 다시쓰기 요청 200(실제 모델 문안, attempt 3, human 1/3),
  미결정 상태 승인 409, 문구 3건 결정 후 승인 200, hitl_status approved와 decided_by 저장, JSONB approval approved와 Agent resume completed, 승인 후 문구 결정 409.
- 미검증: 프론트 화면 연동, 사람 직접 수정 문구의 재검증(미구현), 다시쓰기 동시 요청의 부하 상황.
## 2026-10-20 테스트 결제 준비

토스페이먼츠 테스트용 주문, 승인 경로와 결제 성공 시 1회 적립을 구현했다. 프론트 빌드, Java Maven 테스트, Docker API/웹 health 및 로컬 결제 마이그레이션 적용을 확인했다. 외부 PG의 최종 승인 성공 경로는 아직 검증하지 못했다. 검토 사용량 reserve/charge/release, 환불, 별도 과금 DB는 미구현이다.


## 2026-10-21 근거 수집과 검토 흐름 e2e (feat/review-evidence-pipeline)

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 단위 테스트 | uv run --all-packages --frozen pytest | 61 passed |
| Java | mvn -f services/api/pom.xml verify | 21 tests, 0 failures |
| Frontend | npm run build (vue-tsc 포함) | 성공 |
| 적재 | scripts/ingest_rag.py all | 17개 문서 버전, 1,099개 조각, 조문 링크 309개 |
| 컨테이너 적재 | docker compose run --rm rag-ingest ... --dry-run | 법제처 API 5건, PDF 5건. GitHub 한도 시 화장품법 3건을 법제처 API로 대체 |
| 대조 경로 | 합성 광고(실제 보고품목 편강율블랙티타임리버스아이크림) | 의약품 규칙 제13조 제1항제1호, 자외선차단 mismatch, 히드로퀴논 prohibited, CGMP 줄 규칙 스캔 후 gmp_not_listed, 특허 needs_input |
| 실제 상세페이지 | 848x19799 광고 이미지, OpenAI 키 | 157초, 문구 57건(규칙 스캔 17건) 모두 근거, 실증 미제출 30건, 기능성 보고 미조회 9건 |
| 화면 | 로컬 테스트 계정 | 문구 카드에 규칙, 조문 연결, 대조 결과 표시. 끝난 검토를 다시 열 때 문구 0건으로 보이던 문제 수정 |

## 2026-10-21 검사 크레딧 연결

검사와 재검사 시작 시 회원별 잔액에서 100크레딧을 원자적으로 예약한다. 결과 저장 시 확정하고 분석 실패 시 반환한다. PostgreSQL의 버전별 기록으로 중복 반환을 막는다. 100크레딧 금액권은 5,000원으로 변경하고 나머지는 기존 할인율을 유지했다. `mvn verify`와 PostgreSQL Testcontainers 통합 테스트(잔액 0의 402, 동시 초과 차감 방지, 중복 반환), 프론트엔드 `npm run build`를 통과했다. 실제 PG 승인 성공, 운영 결제, 실제 사용량 정산과 별도 과금 DB는 검증 범위가 아니다.

## 2026-10-21 결제, 검사 설정의 서버 일원화

최신 main 위에서 금액권 가격과 할인율을 `app.credit_packages`, 검사 비용을 `app.credit_policy`로 옮겼다. 결제 주문은 활성 금액권 가격을 서버에서 조회하며 프론트엔드는 `/billing/config`의 금액권, 검사 비용을 표시한다. 결과 화면의 차감 기록도 URL 값 대신 회원별 서버 기록으로 조회한다. Java `mvn test`, 프론트엔드 `npm run build`, 로컬 API, 웹 재빌드, Flyway V10 적용과 API health를 확인했다. 외부 토스 결제 승인은 이번 변경에서 재검증하지 않았다.

CI의 `mvp_smoke.py`는 검사당 크레딧 차감이 추가된 뒤 신규 작성자 계정의 잔액 0으로 업로드에서 402가 났다. 스모크 테스트가 생성한 작성자에게만 DB에서 서버 설정 비용의 두 배를 넣고, 첫 검사와 재검사 후 잔액을 각각 확인하도록 수정했다. 로컬 재실행은 업로드와 차감을 통과했으나, 현재 로컬 Agent의 모델 설정이 CI의 모델 키 없음 조건과 달라 사진 분석 검증 단계에서 멈췄다. CI 전체 통과 여부는 PR 실행으로 확인한다.
