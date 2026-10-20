# 저장소 작업 규칙

- 제품 요구사항의 원본은 https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d 이다. 작업 전에 docs/00-project-context.md와 docs/08-project-memory.md를 읽는다.
- 노션과 오래된 그림 또는 대화가 다르면 최신 노션을 우선한다. 노션 미확정 항목을 확정 사실로 바꾸지 않는다.
- Git 작업 흐름은 docs/05-collaboration.md를 따른다. 원격 main 병합과 브랜치 보호 변경은 별개 작업이다.
- 입력과 OCR 변경 전 docs/09-input-pipeline.md, 분업과 통합 작업 전 docs/10-work-breakdown.md를 읽는다. 설명용 탐색 화면과 제품 기능 구현을 구분한다.
- 모든 직접 작성 자료에서 중앙점을 사용하지 않는다.
- 기능 구현 요청이 없으면 광고 판정, 검색, 결제, 승인 업무를 임의로 완성하지 않는다.
- 기존 파일의 수정은 원래 파일에 반영한다. 별도 제출용 복사본을 만들지 않는다.
- 공통 계약 원본은 packages/shared와 contracts, 설명은 docs에 둔다.
- 인터페이스를 바꾸면 docs/03-contracts.md와 계약 버전을 함께 검토한다.
- 업무 데이터는 Spring Boot의 내부 API를 통해 접근한다. business-mcp에 DB 비밀번호를 추가하지 않는다.
- Agent의 DB 직접 접근은 agent 스키마의 체크포인트만 예외로 허용한다. 검토 이력 CRUD는 Backend, 특허 조회와 대조는 business-mcp 소관이다.
- 원본 업로드는 처리 중 Redis 임시 보관 후 폐기하고 결과는 PostgreSQL에 보존한다. 과금은 Agent 및 MCP 밖의 별도 서비스다.
- rag-mcp는 rules와 cases를 분리한다. 사례는 보조 참고이며 출처 검증 상태를 명시한다.
- 최신 노션의 광고 입력 파싱과 Vision은 Agent, 증빙 파싱은 business-mcp.structure_evidence, 공개 근거 수집은 RAG 배치가 맡는다. prepare_input은 필수 도구가 아닌 이전 확장 제안이다.
- 공개 근거 문서만 knowledge 스키마에 수집한다. 고객 자료는 기업별 권한이 구현된 뒤 별도 설계한다.
- 모델 설명과 원문 인용을 구분한다. 검색 실패나 자료 부족을 적합 판정으로 바꾸지 않는다.
- .env, 고객 파일, 모델 가중치와 DB 덤프를 커밋하지 않는다.
- DB 초기화 스크립트는 빈 개발 DB에만 적용된다. 이미 공유한 DB 변경은 새 마이그레이션으로 작성한다.
- 검증한 범위와 실제 실행하지 못한 범위를 구분해 보고한다.
- Python 검증은 pytest와 계약 JSON 확인, Java 검증은 Maven test/package를 사용한다.
- 실행 중인 사용자 서비스나 DB를 초기화하지 않는다.

## 2026-10-20 노션 반영

- 다건 경로는 Backend → Agent API → RabbitMQ → Agent Worker를 목표로 한다. 단건 큐 통일은 미확정이다.
- Redis는 원본 임시 캐시다. RabbitMQ만으로 업무 재시도와 DLQ, 멱등성이 완성됐다고 표시하지 않는다.
- 탐지, 검색과 대조는 최신 Graph 초안의 한도 있는 도구선택 loop를 따른다.
- MCP와 REST 초안 및 현재 v0.1 실행 계약의 차이는 docs/03-contracts.md에 있다. 계약 PR 없이 이름만 바꾸지 않는다.
- Frontend 드래그앤드롭과 시작 버튼은 이번에는 frontend/README.md의 목표만 관리한다.

- Agent API와 Worker는 Graph 초안의 단일 Agent 서비스 내부 역할이다. 별도 배포 서비스를 임의 추가하지 않는다.
- API 등록부의 /api/v1 목표와 별도 REST 초안의 /api 및 snake_case 불일치를 구분한다. 공개 ID와 내부 Graph 필드를 단순 문자열 치환으로 바꾸지 않는다.
- 다건은 발행 대기 기록 저장 후 202, 승인 대기 checkpoint 저장 후 ACK, 결정 후 재개 메시지 발행을 목표로 한다.
