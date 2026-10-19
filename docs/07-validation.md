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

- 입력 6종: 직접 카피, PNG/JPEG, 텍스트 PDF, 스캔 PDF, 혼합 PDF와 긴 상세페이지.
- 단건 내부 HTTP와 다건 202, Queue 및 Worker 경로 변경.
- 예외 11종: 손상 파일, 잔액 부족, 원본 캐시 소실, 판독 불명, 모델 장애, 검색 없음, 증빙 누락, 공공 API 미조회, 공공 API 장애, 승인 시 원본 부재와 광고 버전 변경.
- 진행 불가 상황에서 다음 단계가 비활성화되고 부분 결과 상황은 이후 검토 단계로 이어짐을 확인.
- 로컬 OCR 선택과 직접 카피의 Vision 생략, 키를 보관할 서버의 표시 확인.
- 320px와 360px 폭에서 가로 넘침 없이 컨트롤과 그래프가 배치됨을 확인.
- JavaScript 구문 검사와 외부 API 호출 코드 부재 확인.

docs/09-input-pipeline.md의 prepare_input, SourceBlock과 오류 코드는 제안 계약이며 코드에 구현하지 않았다.
이번 보완은 Markdown과 PR 템플릿 변경이다. 기존 서버 기능 테스트를 신규 OCR 기능 검증으로 해석하지 않는다.
