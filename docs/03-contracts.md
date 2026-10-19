# 공통 계약 v0.1.0

## 구현된 HTTP

| 엔드포인트 | 현재 의미 |
| --- | --- |
| GET /api/dev/status | Spring Boot에서 DB와 Agent, MCP 연결 상태 집계 |
| GET /internal/bootstrap | 내부 토큰 검증 후 Spring Boot와 DB 연결 확인 |
| GET Agent /health/live | Agent 프로세스 확인 |
| GET Agent /health/ready | Agent DB 연결 확인 |
| GET Agent /internal/environment | 내부 토큰 검증 후 MCP 목록과 DB 확인 |
| POST /api/reviews | HTTP 501, NOT_IMPLEMENTED |
| POST Agent /internal/runs | 내부 토큰 검증 후 HTTP 501, NOT_IMPLEMENTED |

내부 API는 Authorization: Bearer <INTERNAL_SERVICE_TOKEN>을 요구한다.
health 경로는 자격증명을 반환하지 않는다. 현재 사용자 로그인 API는 없다.

## MCP

HTTP 주소는 business-mcp:8002/mcp와 rag-mcp:8003/mcp다.
호스트에서는 각각 127.0.0.1을 사용한다. 인증 방식은 개발용 정적 Bearer 토큰이다.

| 도구 | 현재 상태 |
| --- | --- |
| business.get_environment_info | Spring Boot 내부 API를 실제 호출 |
| business.get_product_context | NOT_IMPLEMENTED |
| rag.search_rules | NOT_IMPLEMENTED, evidence는 빈 목록 |
| rag.search_cases | NOT_IMPLEMENTED, cases 컬렉션을 구분하고 보조 참고 경고 반환 |
| rag.get_evidence | NOT_IMPLEMENTED |

search_rules는 query라는 인자 하나를 받는다. MCP 호출의 arguments 예시는 다음과 같다.

~~~json
{
  "query": {
    "claim_id": "claim-001",
    "claim_text": "수분감 30% 개선",
    "surrounding_text": "광고 주변 문구와 각주",
    "product_type": "기초화장품",
    "jurisdiction": "KR",
    "review_date": "2026-10-19",
    "top_k": 5
  }
}
~~~

top_k는 1~10이다. 정의하지 않은 필드는 거부한다.
tenant_id를 모델 입력에 추가해서 권한을 결정하지 않는다.
현재는 공개 문서 검색을 위한 계약이며 기업 자료 검색 권한은 구현되지 않았다.

반환 상태:

| 상태 | 의미 |
| --- | --- |
| FOUND | 관련 근거 후보 발견, 적법 확정 아님 |
| NO_RELEVANT_EVIDENCE | 적절한 근거 미발견 |
| NEEDS_CONTEXT | 문맥이나 제품 정보 부족 |
| SEARCH_ERROR | 검색 시스템 오류 |
| NOT_IMPLEMENTED | 기능 구현 전 |

Evidence의 quote는 원문이고 context는 연결된 조건 및 주변 원문이다.
결과의 collection은 rules 또는 cases이며 source_verified는 출처 검증 여부다.
search_rules와 search_cases의 입력 모양은 같다. 사례를 1차 법령 근거 목록에 섞지 않는다.
모델이 생성한 쉬운 설명은 Agent 결과의 별도 필드로 관리한다.
페이지는 근거 문서의 페이지다. 광고 위치 좌표와 혼용하지 않는다.

Pydantic 계약은 packages/shared/src/capstone_shared/contracts.py가 원본이다.
contracts의 JSON Schema는 scripts/export_contracts.py로 갱신한다.

## 다음 담당자가 구현할 작업 계약

후속 작업 접수는 task_id, ad_version_id, command_id를 가진다.
다건은 접수와 발행 대기 기록을 저장한 뒤 HTTP 202를 반환한다.
단건은 Backend에서 Agent로 즉시 내부 HTTP 실행하는 경로를 기본 설계로 삼는다.
단건도 내부 큐로 통일할지는 노션의 대안이며 미확정이다.
지금은 501이며 임의 task_id를 생성하지 않는다.

예정된 실행 상태:
RECEIVED → QUEUED 또는 RUNNING → WAITING_INPUT 또는 WAITING_REVIEW → COMPLETED.
실패와 취소는 FAILED와 CANCELLED다. QUEUED는 다건 대기 등 큐에 실제 적재된 경우에만 사용한다.

승인 상태는 실행 상태와 구분해 NOT_REQUESTED, PENDING, APPROVED, REJECTED, CHANGES_REQUESTED와 INVALIDATED를 제안한다.
승인 거부와 API 오류를 같은 상태로 표현하지 않는다. 위 상태는 후속 구현 계약이며 현재 코드의 enum이 아니다.

예정된 SSE:

~~~json
{
  "event_id": "서버가 저장한 이벤트 ID",
  "task_id": "작업 ID",
  "ad_version_id": "광고 버전 ID",
  "type": "EVIDENCE_RETRIEVED",
  "occurred_at": "ISO 8601 timestamp",
  "payload": {"claim_id": "claim-001", "evidence_ids": []}
}
~~~

SSE 인증, 이벤트 저장과 재접속, 승인 권한, command_id 멱등성은 후속 구현 항목이다.
미구현 명세를 실제 동작으로 표시하지 않는다.

## 입력 처리 후속 계약

다음 이름과 필드는 설계안이며 현재 서버의 라우트와 도구 목록에는 없다.
상세한 데이터와 예외 처리는 docs/09-input-pipeline.md를 따른다.

| 계약 | 입력 | 출력과 보장할 경계 |
| --- | --- | --- |
| 광고와 증빙 업로드 | 파일, 역할, 제품 참조와 광고 버전 | asset_id, 해시와 유효성 결과, 인증된 기업 소유 |
| 내부 asset 읽기 | task_id와 asset_id, 서버 인증 | 소유 범위를 검증한 제한된 원본 또는 페이지 |
| business.prepare_input 제안 | asset_id와 자료 역할, 페이지 선택 | 문자 블록, 페이지 정보, 품질과 필요 이미지 콘텐츠 |
| SourceBlock 제안 | 파서 또는 Vision 결과 | asset_id, page, block_id, text, 추출 방법, nullable bbox와 좌표 출처 |
| Claim 제안 | SourceBlock과 문맥 | claim_id, 원문, 주변 문구와 각주, source_block_ids |
| Finding 제안 | 주장과 도구 결과 | 근거 ID, 검토 상태와 미확인 이유, 별도의 모델 설명과 리라이트 |
| 담당자 결정 제안 | task_id, ad_version_id, decision과 command_id | 권한과 광고 버전 재검증, 재개 또는 버전 충돌 |

prepare_input은 Agent가 업무 데이터를 MCP를 통해 받기 위한 제안이다.
파서가 읽은 텍스트와 Vision이 추정한 내용은 추출 방법으로 구분한다.
Vision 결과의 좌표를 정밀 OCR 좌표로 표시하지 않는다. bbox가 null이어도 유효한 페이지 단위 결과다.
사용자 확인 후 수정된 원문은 수정 이력을 남기고 영향받는 주장부터 다시 처리한다.
task와 기업 범위는 Backend가 검증한 실행 문맥에 고정한다. 모델이 tool argument로 tenant를 선택하지 않는다.
공개 규정의 원문 인용과 고객 광고의 원문 블록은 서로 다른 ID와 페이지 체계를 사용한다.

### 오류 응답 제안

~~~json
{
  "code": "INPUT_UNREADABLE",
  "stage": "INPUT_EXTRACTION",
  "task_id": "task-demo-01",
  "asset_id": "asset-demo-01",
  "retryable": false,
  "required_action": "CONFIRM_TEXT",
  "message": "숫자와 각주를 확인해 주세요."
}
~~~

| code | 의미 | 다음 행동 |
| --- | --- | --- |
| INPUT_REJECTED | 손상, 암호화 또는 지원하지 않는 입력 | 유효한 파일 업로드 |
| INPUT_UNREADABLE | 필요한 문자나 숫자를 판독하지 못함 | 확인 또는 재업로드 |
| INPUT_EXPIRED | 필요한 임시 원본이 사라짐 | 해시와 버전을 확인해 재업로드 |
| PROVIDER_UNAVAILABLE | 모델 공급자 일시 장애 | 해당 노드의 제한된 재시도 |
| PROVIDER_CONFIGURATION_ERROR | 키나 모델 설정 오류 | 운영 담당자가 수정 |
| EVIDENCE_MISSING | 필요한 증빙이 없음 | 자료 보완, 다른 검토 결과 유지 |
| LOOKUP_FAILED | 외부 API 호출 실패 | 실패한 조회만 재시도 |
| NOT_FOUND | 정상 조회에서 품목 또는 항목 미발견 | 조회 조건과 원자료 확인 |
| VERSION_CONFLICT | 승인 대상과 현재 광고 버전이 다름 | 새 버전 재검토 |

NO_RELEVANT_EVIDENCE와 SEARCH_ERROR는 현재 RAG 계약의 상태다.
위 오류는 서비스 간 새 계약으로 제안한 항목이며 현재 API가 이미 반환한다고 설명하지 않는다.

## 담당별 후속 계약

- 검토 이력 조회와 삭제: Backend HTTP API. MCP 도구로 만들지 않는다.
- 증빙과 제품, 식약처, 특허 조회 및 대조: business-mcp. 실제 API 샘플 응답 확인 후 시그니처를 확정한다.
- 과금: 별도 서비스의 잔액 사전확인과 사용량 이벤트, 원장. LLM 도구로 노출하지 않는다.
- 사용량 이벤트는 event_id, task_id, ad_version_id, 인증된 tenant 범위, 텍스트 및 이미지 사용량을 후보 필드로 삼는다. 형식은 미확정이다.
- 과금 중복 차감 방지, 예약과 동시성, 중도 실패 및 HITL 대기의 과금 시점은 후속 결정이며 현재 동작을 제공하지 않는다.
