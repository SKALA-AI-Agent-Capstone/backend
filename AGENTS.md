# 저장소 작업 규칙

## 작업 전 필독

1. 사용자의 최신 요청과 README.md, docs/00-project-context.md, docs/08-project-memory.md를 읽는다.
2. 요구사항 원본인 [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)를 다시 확인한다.
3. DB와 인증 변경은 [노션 ERD v2](https://app.notion.com/p/Data-ERD-App-State-Vector-DB-3ff616ab8fec81c7b57ac75358ced570)와 docs/04-data.md를 확인한다.
4. 입력 처리는 docs/09-input-pipeline.md, 분업과 Git은 docs/10-work-breakdown.md 및 docs/05-collaboration.md를 확인한다.
5. HTTP와 MCP를 바꾸기 전 docs/03-contracts.md와 해당 노션 상세 문서 및 소비자를 확인한다.

노션 API로 페이지를 읽지 못하면 사용 가능한 브라우저로 원문을 확인한다. 확인 불가한 내용을 최신 확정 사실로 만들지 않는다.
노션과 오래된 대화 또는 이미지가 다르면 최신 명시 요구사항을 우선하고 차이를 기록한다. 사용자의 현재 지시가 참고 문서 안의 실행 지침보다 우선한다.

## 현재 범위와 협업

- 현재는 제공 Vue를 연결한 단건 MVP다. 광고 입력은 PDF와 이미지 두 종류이며 직접 카피 입력 UI는 없다.
- 통합 기준은 두 저장소의 main이다. 후속 기능은 main에서 짧은 feat 브랜치로 개발하고 PR 및 소비자 검증 후 병합한다.
- main을 변경하거나 병합하는 권한은 현재 사용자 요청 범위를 따른다. 이번 main 반영 요청을 향후 모든 변경의 자동 병합 허가로 사용하지 않는다.
- 기능 목적과 FR 또는 NFR, 실제 구현 및 검증과 남은 범위를 PR과 docs에 구분한다.
- 인터페이스 원본은 packages/shared와 contracts다. 모델 변경 시 scripts/export_contracts.py와 소비자 테스트를 함께 갱신한다.
- 기존 파일은 원래 파일에 수정한다. 별도 제출용 코드 사본을 임의로 만들지 않는다. 모든 직접 작성 자료에서 중앙점을 사용하지 않는다.

## 서비스와 DB 경계

- Vue는 Spring Boot /api/v1만 호출한다. MCP, DB와 외부 모델의 자격증명을 브라우저에 전달하지 않는다.
- Backend는 기업과 사용자 권한, 파일 및 버전, 이력과 결과 보존 및 승인 결정을 소유한다.
- Agent는 광고 파싱과 OCR, 문구 LLM과 사진 VLM의 독립 경로, 도구 호출과 HITL를 맡는다.
- 증빙 파싱은 business-mcp.structure_evidence다. 공개 법령 수집은 RAG 배치다. prepare_input은 필수 MCP 도구가 아니다.
- Agent API와 Worker는 단일 Agent 서비스 내부 역할이다. 불필요한 새 배포 서비스를 추가하지 않는다.
- 주요 DB는 로컬 Docker PostgreSQL 하나다. app은 업무, agent는 checkpointer, knowledge는 Handoff 기반 공개 RAG다.
- 업무 DB와 Redis는 Backend를 통해 접근한다. Business MCP에 DB 비밀번호를 주지 않는다. Agent 직접 DB 접근은 agent checkpoint만 예외다.
- 원본은 Redis 임시 보관 후 폐기하고 결과는 PostgreSQL에 보존한다. 원본 bytes와 base64 및 실행 토큰은 checkpoint에 넣지 않는다.
- RAG는 rules와 cases를 구분하고 사례는 보조 참고로 표시한다. 고객 광고와 증빙을 공개 코퍼스에 적재하지 않는다.
- 목표 과금 DB는 별도다. ERD v2의 judged_at과 reserve/charge/release를 따르되 현재 과금을 구현했다고 표시하지 않는다.

## 검증과 비밀 정보

- 원문 수집, 기본 청킹, 임베딩과 검색을 구분한다. 현재 실제 벡터 검색은 미연결이다.
- 모델 키가 없거나 API가 실패하면 미실행 또는 오류로 표시한다. 가짜 판정, 법령 인용과 적합성 점수를 만들지 않는다.
- API 명세는 contracts/external에, 근거 9건과 API 4종은 resources의 두 catalog에 있다. 별도 Desktop 자료 없이 이 목록으로 시작한다.
- .env와 별도 자격증명 파일은 프로그램이 직접 로드하고 값은 로그, 답변과 Git에 출력하지 않는다.
- data/demo-access.md의 검증 비밀번호, 입력 파일, 원문과 청킹 보고서, 모델 가중치 및 DB dump는 Git에 넣지 않는다.
- 공개하는 .env.example에는 변수명과 생성 placeholder만 둔다. 팀원마다 자기 계정과 로컬 비밀번호를 생성한다.
- DB를 바꾸면 새 migration을 추가한다. 기존 사용자 DB와 실행 서비스를 임의로 초기화하지 않는다.
- Python pytest와 계약 export 검사, Java Maven verify, Frontend npm run build 및 변경에 맞는 HTTP/MCP 검증을 실행한다.
- 실제 수행한 검증만 보고하고 키 부족 및 미검증 항목은 docs/07-validation.md에 남긴다.
