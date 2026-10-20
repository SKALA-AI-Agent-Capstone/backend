# 서비스 인터페이스와 현재 실행 계약

문서 확인일: 2026-10-20. 노션의 명세를 개발 목표로 사용하고, 현재 코드의 v0.1 기반과 구분한다.
이번 보완은 설계 문서 변경이다. 새 업무 HTTP와 MCP 도구를 구현하거나 계약 버전을 올린 작업이 아니다.

## 확인한 노션 문서

| 원본 | 사용할 내용과 주의점 |
| --- | --- |
| [기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d) | FR와 NFR, 저장 정책과 서비스 책임 |
| [MCP 명세](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-3fe616ab8fec818bb82efebe45ca502f) | Business 5개, RAG 2개 도구의 인자, 반환 타입과 외부 API 규칙 |
| [MCP 명세 사본](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-1-3ff616ab8fec804eba66e8a7b2bd99e8) | 확인한 7개 도구의 이름, 인자와 반환 필드는 본문과 같다. 다른 API로 중복 구현하지 않음 |
| [REST 초안](https://app.notion.com/p/Backend-REST-API-3ff616ab8fec81d3b650f8e3186bcf48) | 요청과 응답 및 ReviewResult 설명. 경로는 아직 /api/reviews이고 snake_case 예시가 남아 있음 |
| [API 명세 등록부](https://app.notion.com/p/AI-API-3ff616ab8fec80359149e3682589ca85) | /api/v1 경로와 reviewId, batchId, 회원 API 및 협의 항목. 아래 HTTP 목표 경로는 이 표기를 사용 |
| [Graph 초안](https://app.notion.com/p/Agent-Graph-3ff616ab8fec8127ad6ed17eff1e81b0) | Agent 단일 서비스, 입력 파싱과 도구선택 loop, HITL와 리포트 |
| [실행 시퀀스와 흐름 이미지](https://app.notion.com/p/Agent-RabbitMQ-3ff616ab8fec81c1a513df26b980b638) | 다건 접수와 발행, 승인 대기 저장 후 ACK, 승인 명령 재발행과 재개 |

노션 문서 사이에도 불일치가 있다. 아래 목표 경로가 모든 노션 문서에서 최종 확정됐다는 뜻은 아니다.
경로와 query의 표기는 등록부를 작업 기준으로 삼되, JSON 본문 전체를 camelCase로 바꾸기로 합의했다고 추정하지 않는다.
현재 구현의 사실은 이 문서 마지막의 실행 계약과 소스가 기준이다.

## Frontend와 Backend의 목표 HTTP

등록부에서 확인한 14개 항목이다. 아래 /api/v1 업무 엔드포인트는 현재 실행 코드에 없다.

| 기능 | 메서드와 목표 경로 | 노션에 남은 협의 또는 제약 |
| --- | --- | --- |
| 회원가입 | POST /api/v1/users/signup | 테넌트 생성과 ADMIN 부여, 기존 기업 초대는 별도 흐름 |
| 로그인 | POST /api/v1/users/login | JWT 클레임 userId, tenantId, userRole |
| 로그아웃 | DELETE /api/v1/users/logout | 서버의 Refresh Token 삭제 |
| 단건과 다건 검토 생성 | POST /api/v1/reviews | multipart 배열 key 표기 협의 |
| 단건 결과와 상태 | GET /api/v1/reviews/{reviewId} | 처리 상태와 HITL 상태의 화면 표시 연결 필요 |
| 배치 진행 SSE | GET /api/v1/reviews/stream?batchId={batchId} | 일반 JSON wrapper 제외, 인증 전달 방식 협의 |
| 승인, 반려와 수정요청 | POST /api/v1/reviews/{reviewId}/decision | 검토자와 관리자만 허용 |
| 이력 목록 | GET /api/v1/reviews | ReviewSummary와 페이지네이션 협의 |
| 리포트 | GET /api/v1/reviews/{reviewId}/report | 성공 응답은 PDF 바이너리, JSON wrapper 제외 |
| 개별 이력 삭제 | DELETE /api/v1/reviews/{reviewId} | 기업 범위와 삭제 전파 검증 |
| 전체 이력 삭제 | DELETE /api/v1/reviews | 사용자 확인 UI |
| 재검토 | POST /api/v1/reviews/{reviewId}/re-review | 생략된 파일을 부모 건에서 재사용하는 규칙 협의 |
| 크레딧 잔액 | GET /api/v1/billing/balance | 별도 과금 서비스 연계 |
| 크레딧 충전 목업 | POST /api/v1/billing/charge | 실결제 연동 없음, 원장 반영은 후속 구현 |

광고 입력은 사용자 지시에 따라 PDF와 이미지 파일 두 종류다. REST 초안의 ad_text를 직접 입력 UI로 노출하지 않는다.
텍스트는 파일에서 추출해 내부 State에 전달하는 안이며 PDF 파일 필드, 건별 묶음과 EvidenceFile을 계약 PR에서 고정한다.
파일 선택 후 시작 버튼 UX는 frontend/README.md의 목표만 유지한다.

REST 초안의 ReviewResult는 violations, evidence_match, public_api_check, patent_check, hitl_status와 report_url로 구성된다.
이것은 목표 결과 구조이며 현재 구현된 응답 모델이 아니다. 실행 완료와 사람의 승인 완료를 별도 상태로 표현해야 한다.
단건 초안의 completed 응답을 HITL까지 완료했다는 뜻으로 사용하지 않는다.

SSE의 progress, item_done, batch_done과 이미지의 APPROVAL_REQUIRED는 서로 다른 예시다.
이벤트 이름, 이벤트 ID와 재접속, JWT 전달 방식은 계약에 고정해야 한다. 등록부도 SSE 인증을 협의 필요로 표시한다.
브라우저의 기본 EventSource 인증 제약을 피하려고 토큰을 URL이나 로그에 무조건 넣지 않는다.

## Agent와 MCP의 목표 인터페이스

노션 명세의 이름과 인자를 그대로 작업 기준으로 사용한다. 이 표는 아직 등록되지 않은 목표 도구다.
Agent가 도구를 선택하고 MCP가 검색 또는 사실 대조 결과를 반환한다. 최종 검토 초안과 법률 판단은 Agent가 종합한다.

tenant_id와 review_id는 인증된 실행 문맥에서 바인딩한다. 모델이 다른 기업을 인자로 선택한다고 권한을 얻지 않는다.
EvidenceFile의 필드는 노션에서 타입 이름과 PDF 또는 이미지 참조라는 설명만 제공하므로 임의 URL fetch를 허용하는 구현으로 바꾸지 않는다.

| 서버와 도구 | 입력 | 반환 타입 | 책임과 조건 |
| --- | --- | --- | --- |
| business.structure_evidence | tenant_id, review_id, documents: EvidenceFile 목록 | EvidenceStructured | 증빙 PDF와 이미지를 구조화, 파싱 실패를 누락과 낮은 판독 품질로 구분 |
| business.match_product_claims | ad_claims: 문자열 목록, evidence, api_approval=None | ClaimMatchResult 목록 | found인 API 결과가 있으면 그 효능 범위를 우선하고 나머지는 제출 증빙 기준 |
| business.verify_functional_approval | functional_approval_no, product_name, entp_name=None, bizrno=None, origin_country=None | ApprovalVerifyResult | 번호와 품목명으로 식약처 보고품목을 보조 교차검증 |
| business.verify_patent | patent_numbers: 문자열 목록 | PatentVerifyResult 목록 | 원문 번호 정규화와 실제 조회, 각 번호별 결과 반환 |
| business.match_patent_claims | patent: PatentVerifyResult, ad_claims: 문자열 목록 | PatentClaimMatchResult | registered 결과만 대조. 현재 초안은 명칭과 초록 및 IPC 범위 |
| rag.search_regulations | query_text: 문자열, top_k=5 | RegulationHit 목록 | 컬렉션 A의 법령과 기준만 검색 |
| rag.search_precedents | query_text: 문자열, top_k=3 | PrecedentHit 목록 | 컬렉션 B 사례만 보조 검색, verified=False 고정 |

### RAG 반환 필드

| 타입 | 노션 필드 | 사용 규칙 |
| --- | --- | --- |
| RegulationHit | source, version, scope, excerpt, similarity, trust_level | trust_level은 high, 출처와 개정 버전 및 적용 범위 필수 |
| PrecedentHit | source, excerpt, similarity, trust_level, verified | trust_level은 low, verified는 False, UI에 출처 미검증 표시 |

excerpt는 저장된 원문이다. 쉬운 설명이나 리라이트를 섞지 않는다.
similarity는 검색 유사도이며 위법 확률이 아니다. high와 low는 이 명세에서 자료의 역할을 구분하는 값이다.
법령과 협회 기준도 법적 효력이 같다고 해석하지 않는다.

현재 v0.1의 quote는 목표 excerpt, document_version은 목표 version과 대응할 수 있지만 단순 이름 변경으로 호환되지 않는다.
기존 source_url, page, section, evidence_id, review_date와 corpus_version의 추적 기능을 어떻게 보존할지 계약 PR에 적는다.
목표 명세는 목록 반환만 제시하므로 빈 검색과 검색 장애, 미구현을 구분하는 MCP 오류 계약도 함께 필요하다.
get_evidence는 기존 기반의 확장 제안이며 노션의 필수 RAG 도구 2개에 포함되지 않는다.

### Business 반환 필드

| 타입 | 핵심 필드 | 검토할 제약 |
| --- | --- | --- |
| EvidenceStructured | review_id, functional_approval_no, product_name, entp_name, bizrno, approved_claims, spf, pa, ingredients, cert_numbers, patent_numbers_raw, origin_country, confidence, missing_fields | 문서별 실패를 표현한다는 설명에 비해 현재 타입의 confidence는 합계 하나다. 문서별 오류 위치는 보완 필요 |
| ClaimMatchResult | claim, within_approved_scope, flagged, basis, reason | basis는 api 또는 evidence. 자료 부족을 bool 두 개로 어떻게 표현할지 보완 필요 |
| ApprovalVerifyResult | status, matched_by, 품목 및 보고 식별값, approved_claims, efficacy_doc_text, spf, pa, water_proofing, manuf_country, mismatches, confidence, source_ref, error_code, checked_at | spf와 pa를 불일치 항목으로 반환하려면 비교할 광고 또는 증빙 수치를 전달하는 경계 필요 |
| PatentVerifyResult | input_raw, normalized_number, number_type, right_type, status, register_status, invention_title, abstract, ipc_numbers, applicant_names, 번호와 날짜, confidence, source_ref, error_code, checked_at | 특허와 실용신안, 공개와 등록 상태 구분. 검색 실패와 부재를 혼동하지 않음 |
| PatentClaimMatchResult | register_number, application_number, compared_fields, results, ipc_cosmetic_related, reasoning, source_ref | results의 각 ClaimRelation은 ad_claim, status, evidence_field, evidence_excerpt |

ApprovalVerifyResult.status는 found, cancelled, not_found, ambiguous, insufficient_input, api_error다.
PatentVerifyResult.status는 registered, lapsed, pending, rejected, not_found, ambiguous, invalid_format, api_error다.
ClaimRelation.status는 related, unrelated, unverifiable다. 실제 API 조회 결과와 Agent의 검토 판단을 같은 상태로 합치지 않는다.

## 외부 API adapter에 이미 적힌 규칙

| 항목 | 노션에 적힌 처리 | 아직 실응답으로 확인할 것 |
| --- | --- | --- |
| 식약처 getRptPrdlstInq01 | MFDS_SERVICE_KEY, 번호 후보와 품목명 조회, 업체 식별값 필터, JSON 기본 및 게이트웨이 XML 오류 처리 | Swagger base URL, 정상 resultCode, 증빙 번호와 ITEM_SEQ 및 COSMETIC_REPORT_SEQ 및 DEPT_RECEIPT_NO의 관계 |
| 식약처 응답 정규화 | 0건과 1건 객체 및 다건 배열 정규화, 문서 마크업과 날짜 처리 | EE_DOC_DATA 형식, EFFECT_YN별 의미, 이름 검색 방식과 날짜 형식 |
| KIPRIS getWordSearch | KIPRIS_SERVICE_KEY, XML 파싱, 번호 정규화 후 registerNumber/applicationNumber/openNumber 정확 일치 | 13자리 번호 검색의 실제 동작과 결과 페이지 완전성 |
| 특허 범위 대조 | 명칭, 초록과 IPC 재사용. 초록이 없으면 무관하다고 단정하지 않음 | 청구항 원문을 제공할 별도 오퍼레이션 |

첫 페이지에 일치 항목이 없더라도 뒤 페이지가 있으면 not_found로 단정하지 않는 것이 현재 명세의 규칙이다.
키를 tool 인자와 로그에 넣지 않는다. 어댑터는 키 인코딩과 원본 형식을 실제 샘플로 검증해야 한다.
업체정보 getRptEntpInq01, 상표와 디자인 검증은 현재 7개 도구의 확정 범위에 임의로 추가하지 않는다.

## 문서 사이에서 해결해야 할 차이

| 항목 | 관찰한 차이 | 처리 기준 |
| --- | --- | --- |
| HTTP 표기 | 등록부는 /api/v1과 reviewId, batchId, REST 초안은 /api와 snake_case | 이 문서의 목표 경로는 등록부 사용, 요청 및 응답의 필드 매핑을 계약 PR로 고정 |
| 승인 대기 | REST 초안은 processing/completed/failed만 제시하고 Graph는 interrupt 대기 | 실행 상태와 hitl_status 분리, pending 화면과 중간 결과 계약 보완 |
| 식약처 호출 조건 | MCP는 품목명 fallback을 지원하지만 Graph는 심사번호가 있을 때만 호출 | 번호 없는 제품의 품목명 조회를 loop에 허용할지 Graph와 도구 계약을 함께 조정 |
| 광고 속 특허번호 | FR-14는 광고와 증빙 모두 대상, Graph 조건은 증빙 patent_numbers_raw 중심 | Agent 입력 노드의 patent_numbers_detected도 조회 후보에 연결 |
| 특허 대조 깊이 | FR-15 본문은 청구항과 명세서, 현재 MCP는 명칭과 초록 및 IPC만 제공 | 현 범위를 초록 수준 관련성 대조로 표시, 청구항 검증 완료로 표현하지 않음 |
| 반려와 수정요청 | Graph는 새 review_id로 재실행, 흐름 이미지는 검토 완료, 수정안 승인과 자료 보완을 구분 | 모든 반려를 자동 유료 재검토로 보내지 않음. 사용자 재검토 명령과 버전 생성 규칙 합의 |
| 원본 재사용 | 등록부의 re-review는 생략 파일 재사용을 협의 필요로 표시, 원본은 임시 보관 | 폐기한 원본을 재사용할 수 있다고 가정하지 않음. 해시 확인과 재업로드 정책 연결 |
| 202와 queued | 시퀀스 이미지는 발행 전 작업과 발행 대기 기록 저장 후 202, REST 예시는 queued 반환 | 영구 접수와 브로커 적재 상태 분리, 발행 실패는 저장된 기록에서 복구 |

이 목록은 모든 개발을 멈추라는 뜻이 아니다. 7개 도구의 책임, RAG 검색과 공개 문서 수집은 명세를 기준으로 진행한다.
불일치가 있는 필드와 상태 전이만 담당자가 계약 PR에서 함께 해결한다.

## 다건과 HITL 전달 경계

Agent API, Worker와 Graph는 노션 Graph에 따라 하나의 Agent 서비스 안의 역할이다. 별도 배포 서비스를 추가하지 않는다.
내부 역할 모듈과 Worker 라이브러리, 실행 시작 방식은 후속 구현이다.

1. Backend가 권한과 잔액을 확인하고 작업 및 발행 대기 기록을 저장한 뒤 다건 202를 반환한다.
2. Backend가 Agent API에 실행 명령을 전달하고 Agent가 RabbitMQ에 작업 참조를 발행한다.
3. confirm은 브로커 수신 확인이다. Worker는 소비한 메시지로 같은 Graph를 실행한다.
4. 초안과 HITL checkpoint를 영구 저장하고 APPROVAL_REQUIRED를 전달한 뒤 해당 메시지를 ACK한다. 사람을 기다리며 메시지를 계속 점유하지 않는다.
5. Backend가 담당자의 권한과 광고 버전을 재검증해 결정을 저장하고 Agent에 재개 명령을 전달한다.
6. Agent가 재개 작업을 발행하고 Worker가 checkpoint와 결정으로 이어서 실행한다. 변경된 광고는 새 버전 규칙을 적용한다.

이미지의 taskId와 commandId는 메시지용 예시다. reviewId, batchId와의 관계와 메시지 스키마 버전은 후속 계약이다.
원본 바이트, 공급자 API 키와 사용자 JWT를 작업 메시지나 checkpoint에 그대로 넣지 않는다.
중복 command, 발행 대기 기록과 실제 큐 적재의 어긋남, 저장 후 ACK의 멱등성을 검증해야 한다.

## 현재 기반 코드와의 연결

아래 내용은 실행 가능한 개발 기반 v0.1이다. 위 7개 목표 도구와 /api/v1 업무 API의 구현 완료를 뜻하지 않는다.
후속 계약 PR에서 packages/shared, contracts, MCP 도구와 소비자 및 테스트를 함께 전환한다.
소스와 잠금 파일은 이번 문서 보완에서 변경하지 않는다.

## 현재 실행 코드의 HTTP

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

## 현재 실행 코드의 MCP v0.1

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
    "claim_text": "피부 장벽 개선 및 48시간 보습 지속",
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


## 입력과 오류 확장 제안

광고 SourceBlock과 페이지 및 좌표는 Agent 입력 담당, 증빙 EvidenceStructured는 Business MCP,
RegulationHit와 PrecedentHit는 RAG 담당의 출력이다. 광고 위치와 근거 문서 위치를 구분한다.

prepare_input, SourceBlock, Claim, Finding과 get_evidence는 이전 기반의 확장 제안이다.
노션의 7개 도구와 별개로 표기하고 추가 필요가 있을 때만 별도 계약으로 채택한다.
기존 문서의 INPUT_REJECTED, INPUT_UNREADABLE, INPUT_EXPIRED, PROVIDER_UNAVAILABLE,
PROVIDER_CONFIGURATION_ERROR, EVIDENCE_MISSING, LOOKUP_FAILED, NOT_FOUND와 VERSION_CONFLICT도 후속 오류 코드 제안이다.
현재 외부 조회의 status와 공개 HTTP 오류를 이 코드로 이미 통일했다고 표시하지 않는다.

검토 이력 조회와 삭제는 Backend JDBC 경로다. 과금과 크레딧 원장, 잔액 확인과 사용량 이벤트는
Agent 및 MCP 밖의 별도 Application 서비스가 책임진다. 중도 실패와 HITL 대기의 과금 시점은 미확정이다.
