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
