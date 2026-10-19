# Git 협업 가이드

> 적용 대상: SKALA-AI-Agent-Capstone/backend와 frontend.
> 상태: 팀 검토용 초기 운영안. 노션에는 Git 브랜치 정책이 없어 이 문서를 개발 규칙의 제안으로 작성했다.
> 원격 브랜치 보호, 승인 인원과 자동 병합 설정은 변경하지 않았다.

## 1. 기준과 문서

| 구분 | 기준 위치 |
| --- | --- |
| 서비스 요구사항 | [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d) |
| 아키텍처와 입력 처리 | docs/01-architecture.md, docs/09-input-pipeline.md |
| HTTP와 MCP 계약 | packages/shared, contracts, docs/03-contracts.md |
| 담당과 작업 순서 | docs/10-work-breakdown.md |
| 작업 메모리 | AGENTS.md, docs/08-project-memory.md |

기능의 목적은 노션 FR 또는 NFR에 연결한다. 노션에서 미확정인 결정은 PR에 제안으로 표시한다.
공통 명세를 Frontend에 복제하지 않고 관련 Backend 문서와 PR을 연결한다.

## 2. 저장소와 브랜치

| 구분 | 용도 | 예 |
| --- | --- | --- |
| main | 팀이 검토한 통합 기준 | 기능 브랜치의 출발점 |
| feat/* | 사용자 또는 서버 기능 | feat/rag-rule-search |
| fix/* | 재현 가능한 오류 수정 | fix/ingest-page-rotation |
| docs/* | 설계와 개발 문서 | docs/input-processing |
| chore/* | 실행환경과 유지 관리 | chore/dev-foundation |

브랜치 이름은 영문 소문자와 하이픈으로 작성하고 작업 목적을 담는다.
chore는 Vue를 뜻하지 않는다. 현재 chore/dev-foundation에는 Backend와 Frontend의 초기 실행환경이 각각 들어 있다.
새로운 검색과 OCR 같은 기능을 계속 chore 브랜치에 몰아넣지 않는다.

~~~mermaid
flowchart LR
  M[main] --> F[feat/rag-rule-search]
  F --> D[Draft PR]
  D --> C[검증과 담당자 리뷰]
  C --> S[Squash merge 제안]
  S --> N[main]
~~~

초기에는 main과 짧은 작업 브랜치를 사용한다. develop과 release 브랜치는 필요가 생겼을 때 팀 합의로 추가한다.
한 PR은 하나의 검토 가능한 작업을 대상으로 한다. 파서 교체와 로그인 기능처럼 관계없는 작업을 섞지 않는다.
main 직접 push와 공유 브랜치 force push는 하지 않는다. main 병합은 팀 리뷰 후 수행한다.

## 3. 처음 개발환경 받기

기반 PR이 아직 main에 병합되지 않았다면 두 저장소 모두 같은 기반 브랜치를 받는다.

~~~sh
git clone --branch chore/dev-foundation https://github.com/SKALA-AI-Agent-Capstone/backend.git
git clone --branch chore/dev-foundation https://github.com/SKALA-AI-Agent-Capstone/frontend.git
cd backend
python3 scripts/bootstrap.py
docker compose --profile web up --build --wait
~~~

main에 기반이 병합된 뒤에는 일반 clone과 main을 사용한다.
실행 상세와 담당별 호스트 개발은 docs/02-development.md를 따른다.

## 4. 기능 작업의 순서

1. 작업 카드에 목적, 관련 FR 또는 NFR, 입력과 출력, 오류 사례, 완료 조건을 적는다.
2. 최신 main에서 작업 브랜치를 만든다. 기반 미병합 상태의 종속 작업은 아래 규칙을 따른다.
3. 계약이 바뀌면 소비자 담당자에게 먼저 공유한다.
4. 기능을 구현하고 관련 검증을 실행한다.
5. Draft PR을 열고 필요한 다른 저장소 PR을 연결한다.
6. 코드와 실행 증거가 준비되면 팀 리뷰를 요청한다.
7. 리뷰와 CI 확인 후 팀의 병합 담당자가 병합한다.

~~~sh
git switch main
git pull --ff-only origin main
git switch -c feat/rag-rule-search
# 담당 파일 수정
git add services/rag-mcp packages/shared contracts docs
git diff --cached
git commit -m "feat(rag): retrieve rules with source citations"
git push -u origin feat/rag-rule-search
~~~

기반 PR 병합 전에는 chore/dev-foundation에서 기능 브랜치를 만들고 Draft PR의 base를 해당 기반 브랜치로 둔다.
기반 병합 후 main을 통합하고 PR의 base를 main으로 바꿔 기반 변경이 중복 표시되지 않는지 확인한다.
다른 사람이 공유한 브랜치를 임의로 rebase하거나 force push하지 않는다.

## 5. 커밋 메시지

~~~text
<type>(<scope>): <변경의 목적>
~~~

| type | 사용 시점 | 예 |
| --- | --- | --- |
| feat | 새 기능 | feat(rag): add separate case retrieval |
| fix | 오류 수정 | fix(ingest): preserve footnotes across tiles |
| docs | 문서 수정 | docs(flow): describe PDF and image processing |
| test | 검증만 추가 또는 수정 | test(agent): cover unreadable input |
| refactor | 동작을 유지한 구조 변경 | refactor(business): share document parser |
| chore | 의존성과 개발환경 | chore(infra): prepare Redis cache |

scope 후보는 web, api, agent, business, rag, ingest, contracts와 infra다.
메시지는 한 변경의 목적을 설명한다. 작업 중, 수정, 최종 같은 의미 없는 제목은 피한다.
이미 공개된 커밋을 정리하려고 공유 이력을 강제로 바꾸지 않는다.

## 6. Pull Request 규칙

PR 제목은 변경 후 동작을 설명하고, 본문은 저장소의 템플릿을 따른다.

- 관련 요구사항 또는 작업 카드와 목적.
- 변경한 서비스와 파일 범위, 영향받는 소비자.
- 정상 동작과 중요한 실패 동작.
- 계약과 DB 변경, 호환성 및 적용 순서.
- 실행한 검증 명령과 결과, 실행하지 못한 항목.
- 남은 범위와 종속 PR.

화면 변경은 재현 가능한 입력과 화면 증거를 첨부한다.
모델 응답을 캡처할 때는 실제 고객 내용과 자격증명을 제거한다.
샘플 응답과 실제 API 응답을 구분한다. NOT_IMPLEMENTED를 통합 성공으로 표현하지 않는다.

## 7. 누가 리뷰하나

| 변경 범위 | 함께 확인할 역할 |
| --- | --- |
| 업로드와 추출 형식 | Frontend, Backend, 입력 처리와 Agent |
| MCP 도구와 공통 계약 | 도구 구현자와 Agent, 영향받는 Frontend |
| 법령과 사례 검색 | RAG와 Agent |
| 기업 권한과 원본 수명 | Backend와 입력 처리, Frontend |
| Graph State와 HITL | Agent와 Backend, Frontend |
| DB와 환경변수 | 해당 서비스와 공통 환경 담당 |
| 사용량과 원장 | Backend, Agent와 과금 담당 |

담당자의 실제 이름과 GitHub 리뷰 강제 설정은 팀이 정한다.
동료 리뷰 권장과 GitHub가 실제로 강제하는 보호 규칙을 구분한다.

## 8. 계약과 DB 변경

1. packages/shared의 Pydantic 원본과 docs/03-contracts.md를 함께 수정한다.
2. scripts/export_contracts.py로 JSON Schema를 생성한다.
3. 관련 Frontend 타입과 다른 서비스 소비자를 확인한다.
4. 호환되지 않는 변경은 계약 버전과 병합 및 배포 순서를 제안한다.
5. Backend와 Frontend 양쪽 PR에 상대 PR 링크를 적는다.

공유 DB 변경은 새 마이그레이션으로 작성한다. 다른 팀원의 DB 볼륨을 초기화하는 명령을 일반 개발 절차로 쓰지 않는다.
GitHub에 DB 데이터를 올려 공유하지 않는다. 스키마와 합성 예제만 버전 관리한다.

## 9. 충돌과 공통 파일

main 변경은 작업 브랜치에 일반 merge로 반영하는 방식을 기본 제안으로 둔다.
작업 트리가 깨끗한지 확인한 뒤 진행하고, 충돌이 나면 파일 담당자와 동작을 확인한다.

~~~sh
git fetch origin
git merge origin/main
# 충돌 해결과 검증 후 커밋
~~~

uv.lock과 package-lock.json 충돌은 양쪽 문자열을 임의로 이어 붙이지 않는다.
의존성 선언을 먼저 합의하고 잠금 파일을 해당 도구로 다시 생성한다.
compose.yaml, 공통 계약과 DB 스키마는 다른 작업에 영향이 크므로 PR에 변경 사실을 명시한다.

## 10. 완료 체크리스트

- [ ] 요구사항과 변경 목적이 설명되어 있다.
- [ ] 정상과 중요한 오류 사례를 확인했다.
- [ ] 해당하는 Python 테스트, Maven verify 또는 npm run build를 통과했다.
- [ ] 연결 변경이 있으면 실제 HTTP 또는 MCP 호출을 확인했다.
- [ ] 계약과 문서 및 필요한 마이그레이션을 갱신했다.
- [ ] 비밀번호, 키, 원본 고객 파일과 DB 덤프가 없다.
- [ ] 영향받는 담당자와 종속 PR을 연결했다.
- [ ] 구현과 제안, 미구현 범위를 구분했다.

## 11. 결정 기록

요구사항 변경은 노션을 먼저 확인한다. 구현 중 합의한 기술 결정은 관련 문서에 근거와 상태를 적는다.
팀 합의가 필요한 내용은 임의로 노션 확정 요구사항으로 바꾸지 않는다.
관련 작업이 끝나면 docs/08-project-memory.md의 오래된 가정과 다음 작업도 함께 갱신한다.
