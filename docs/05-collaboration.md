# 분업과 Git 작업 흐름

제품과 서비스 경계는 [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)를 따른다.
노션에는 Git 브랜치 정책이 없으므로 아래는 두 저장소에 적용할 초기 운영 제안이다.
원격 브랜치 보호나 병합 정책을 자동 변경하지 않는다.

## 담당 경계

| 영역 | 작업 위치 | 주요 합의 |
| --- | --- | --- |
| Frontend | frontend 저장소 | 입력, 광고 좌표, 상태, SSE와 승인 UI |
| 업무 Backend | backend/services/api | JWT와 기업 권한, 이력 CRUD, 광고 버전, 내부 API |
| Agent | backend/services/agent | Graph State, 노드 입출력, 체크포인트, HITL와 이벤트 |
| Business MCP | backend/services/business-mcp | 증빙, 제품 대조, 식약처, 특허 조회와 대조 |
| RAG MCP | backend/services/rag-mcp | rules와 cases, 수집과 검색, 정확한 출처 및 평가 |
| 다건 처리 | 큐 설정과 후속 Worker | 작업 발행, 재시도, 중복 통제와 실패 상태 |
| 과금 | 후속 별도 서비스와 DB | 잔액, 사용량 이벤트, 원장, 충전 목업 |
| 공통 환경 | compose.yaml, infra, packages/shared | 실행환경, 스키마, 계약과 검증 |

사람 이름과 일정은 팀이 배정한다. RAG 담당이 Agent나 특허 검증을 중복 구현하지 않는다.

## 브랜치 정책

초기에는 main과 짧은 기능 브랜치를 쓰는 단순한 흐름을 제안한다.
develop을 추가하거나 저장소를 더 쪼개는 결정은 팀 합의 후 반영한다.

~~~text
main
  └─ feat/rag-ingest → Draft PR → 리뷰와 CI → main
  └─ feat/rag-search → Draft PR → 리뷰와 CI → main
  └─ feat/agent-graph → Draft PR → 리뷰와 CI → main
  └─ chore/dev-foundation → 기반 검토 PR → main
~~~

- main은 공유 통합 기준이다. 기능 브랜치는 최신 main에서 시작한다.
- 접두사는 feat, fix, docs, chore 중 목적에 맞게 선택한다.
- 기능 단위로 PR을 나눈다. 다른 담당자 코드를 한 PR에서 임의로 완성하지 않는다.
- main 직접 push와 공유 브랜치 force push는 하지 않는다.
- 최종 병합은 팀 리뷰 후 수행한다. 기반 준비 단계에서는 Draft PR까지만 만든다.

~~~sh
git switch main
git pull --ff-only origin main
git switch -c feat/rag-search
# 담당 코드 수정과 검증
git add services/rag-mcp packages/shared contracts docs
git commit -m "feat: add public rules retrieval"
git push -u origin feat/rag-search
# GitHub에서 main 대상으로 Draft PR 생성
~~~

이 기반이 main에 병합되기 전 종속 작업을 시작하면 chore/dev-foundation에서 분기할 수 있다.
이 경우 PR에 기반 PR 의존성을 명시하고, 기반 병합 후 대상과 차이를 다시 확인한다.

## 저장소 간 계약 변경

1. 노션 요구사항을 확인하고 해당 FR 또는 NFR을 PR에 적는다.
2. backend/docs/03-contracts.md와 packages/shared의 계약을 먼저 제안한다.
3. export_contracts.py로 contracts의 JSON Schema를 갱신한다.
4. Frontend가 영향을 받으면 대응 PR을 별도로 만들고 양쪽 PR 링크를 연결한다.
5. 계약과 DB 변경은 해당 소비자 담당자의 리뷰를 받는다.
6. 호환되지 않는 변경은 서버와 화면의 병합 및 배포 순서를 PR에 적는다.

JSON Schema가 Python 계약 원본의 생성물이므로 두 곳을 독립적으로 손수 수정하지 않는다.
DB 데이터나 .env를 Git으로 동기화하지 않는다.

## 검증과 완료 기준

- Python: pytest, JSON Schema 일치 확인.
- Java: Maven verify.
- Frontend: npm run build.
- 통합: Compose 실제 기동과 scripts/smoke.py. CI 설정 파일만으로 성공했다고 표시하지 않는다.
- 비밀번호와 토큰, 업로드 원본, 모델 가중치와 DB 덤프가 포함되지 않아야 한다.
- 미구현 응답과 실제 결과를 구분한다. 인용문, 모델 설명과 검색 순위의 의미를 섞지 않는다.
- 실행 방법, 검증 결과와 남은 범위를 PR에 적는다.

## 문서와 메모리 갱신

노션 변경 확인 → docs/00-project-context.md의 요구사항 매핑과 미확정 항목 수정
→ 관련 계약 및 아키텍처 반영 → docs/08-project-memory.md에 결정과 변경 근거 기록.
AGENTS.md는 다음 작업자가 이 순서를 읽게 하는 진입점이다. 대화의 오래된 가정을 확정 요구로 복원하지 않는다.
