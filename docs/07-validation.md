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
