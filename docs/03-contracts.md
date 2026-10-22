# 서비스 인터페이스와 현재 실행 계약

## 저장소 분리 이후 계약 위치

HTTP와 MCP의 필드와 상태는 이번 이식에서 변경하지 않는다. Python 모델 원본은 ai_agent/packages/shared,
생성 스키마와 외부 API 명세는 ai_agent/contracts로 이동했다. Backend와 Frontend는 이 계약의 소비자다.
기존 문서의 packages/shared와 contracts 경로는 AI 저장소 기준으로 읽는다.
공개 출처 목록은 ai_agent/resources가 원본이며 backend classpath 스냅샷과 바이트 일치를 검사한다.


문서 확인일: 2026-10-20. 노션의 명세를 개발 목표로 사용하고, 현재 코드의 v0.1 기반과 구분한다.
이번 보완은 설계 문서 변경이다. 새 업무 HTTP와 MCP 도구를 구현하거나 계약 버전을 올린 작업이 아니다.

## 확인한 노션 문서

| 원본 | 사용할 내용과 주의점 |
| --- | --- |
| [기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d) | FR와 NFR, 저장 정책과 서비스 책임 |
| [MCP 명세](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-1-3ff616ab8fec804eba66e8a7b2bd99e8) | Business 5개, RAG 2개 도구의 인자, 반환 타입과 외부 API 규칙 |
| [MCP 명세 사본](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-1-3ff616ab8fec804eba66e8a7b2bd99e8) | 확인한 7개 도구의 이름, 인자와 반환 필드는 본문과 같다. 다른 API로 중복 구현하지 않음 |
| [REST 초안](https://app.notion.com/p/Backend-REST-API-3ff616ab8fec81d3b650f8e3186bcf48) | 요청과 응답 및 ReviewResult 설명. 경로는 아직 /api/reviews이고 snake_case 예시가 남아 있음 |
| [API 명세 등록부](https://app.notion.com/p/AI-API-3ff616ab8fec8047bf0ec0ec5e508560) | /api/v1 경로와 reviewId, batchId, 회원 API 및 협의 항목. 아래 HTTP 목표 경로는 이 표기를 사용 |
| [Graph 초안](https://app.notion.com/p/Agent-Graph-3ff616ab8fec8127ad6ed17eff1e81b0) | Agent 단일 서비스, 입력 파싱과 도구선택 loop, HITL와 리포트 |
| [실행 시퀀스와 흐름 이미지](https://app.notion.com/p/Agent-RabbitMQ-3ff616ab8fec81c1a513df26b980b638) | 다건 접수와 발행, 승인 대기 저장 후 ACK, 승인 명령 재발행과 재개 |

노션 문서 사이에도 불일치가 있다. 아래 목표 경로가 모든 노션 문서에서 최종 확정됐다는 뜻은 아니다.
경로와 query의 표기는 등록부를 작업 기준으로 삼되, JSON 본문 전체를 camelCase로 바꾸기로 합의했다고 추정하지 않는다.
현재 구현의 사실은 이 문서 마지막의 실행 계약과 소스가 기준이다.

## Frontend와 Backend의 목표 HTTP

등록부에서 확인한 목표 14개 항목이다. 실제 구현된 일부 /api/v1 경로와 응답 차이는 아래 단건 최소 기능 계약 절을 따른다.

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
| 테스트 결제 설정 | GET /api/v1/billing/config | 테스트 키 구성 여부, 토스 공개 클라이언트 키, 활성 금액권 목록(packages)과 검사 비용(reviewCost) |
| 크레딧 잔액 | GET /api/v1/billing/balance | 로그인 회원의 app.users.credit_balance, JWT 회원, 기업 범위 |
| 검사 크레딧 기록 | GET /api/v1/billing/reviews/{reviewId}/charge?versionNo={no} | 로그인 회원 자신의 버전별 차감 금액과 상태. 기록이 없으면 charged=false |
| 충전 주문 | POST /api/v1/billing/orders | credits와 method(toss), 가격은 서버에서 확정 |
| 토스 테스트 승인 | POST /api/v1/billing/orders/{orderId}/confirm-toss | paymentKey 및 금액 대조 후 1회 적립 |

광고 입력은 사용자 지시에 따라 PDF와 이미지 파일 두 종류다. REST 초안의 ad_text를 직접 입력 UI로 노출하지 않는다.
텍스트는 파일에서 추출해 내부 State에 전달하는 안이며 PDF 파일 필드, 건별 묶음과 EvidenceFile을 계약 PR에서 고정한다.
파일 선택 후 시작 버튼 UX는 frontend/README.md의 목표만 유지한다.

REST 초안의 ReviewResult는 violations, evidence_match, public_api_check, patent_check, hitl_status와 report_url로 구성된다.
이것은 목표 결과 구조이며 현재 구현된 응답 모델이 아니다. 실행 완료와 사람의 승인 완료를 별도 상태로 표현해야 한다.
단건 초안의 completed 응답을 HITL까지 완료했다는 뜻으로 사용하지 않는다.

2026-10-20 HITL 재설계로 위 단일 hitl_status는 두 층위로 나뉘었다(ERD는 app.violations/
app.violation_rewrites/reviews.hitl_status로 이미 반영, Agent 쪽 구현은
services/agent/src/capstone_agent/review_engine.py의 violations 노드 참고. 사람의 최종
violation 단위 결정을 받는 REST 필드 자체는 아직 계약 PR 전이라 이 문서의 ReviewResult
설명도 그 PR에서 함께 바꾼다):
violations[*]마다 kept/rewritten/rewritten_manual 중 하나로 사람이 결정하는 violation 단위
상태, 그리고 그와 별개로 review 전체의 승인/반려/원본재업로드요청 최종 결정. 후자는 모든
violation이 결정됐는지와 무관하게 내려질 수 있다 — violation을 전부 원문 유지로 두고
review를 승인하는 것도 허용되는 사람의 업무 판단이다.

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
| rag.lookup_claim_rules | phrase, claim_type=None, review_date=None | ClaimRulesResult | 2026-10-21 추가. 규칙표(claim_rules.json)로 정해진 현행 조항, 호, 연결 조항, 실증 별표 행, 실행할 대조를 돌려준다 |
| rag.scan_claim_lines | lines: 문자열 목록 | 규칙 패턴에 걸린 줄 목록 | 2026-10-21 추가. 모델이 놓친 고위험 줄 보충용. DB를 쓰지 않는다 |
| rag.check_ingredient_standard | ingredient, review_date=None | prohibited, restricted, not_listed | 2026-10-21 추가. 화장품 안전기준 별표 1, 2를 표 행 단위로 정확 대조 |
| business.find_functional_reports | product_name, functional_approval_no=None | 정확 일치와 후보 보고품목, 기능 | 2026-10-21 추가. 보고번호 없이 제품명으로 조회하고 효능효과 문구로 기능을 판별 |
| business.verify_gmp_company | company_name | 상태와 일치 업체 목록 | 2026-10-20 추가. 식약처 GMP 적합 업체현황(API03) 보조 확인 |
| business.lookup_ingredient_regulation | ingredient_name | 상태와 일치 성분 목록 | 2026-10-20 추가. 식약처 화장품 규제정보(API04)의 금지 및 제한 국가 |

2026-10-20 feat/rag-legal-sources 브랜치에서 rag.search_regulations와 rag.search_precedents를 실제 등록했다.
노션은 목록 반환만 적었지만, 빈 결과와 장애를 구분하려고 status를 가진 봉투로 감쌌다.
반환 스키마는 contracts/search-regulations.output.schema.json, contracts/search-precedents.output.schema.json이다.
status는 FOUND, NO_RELEVANT_EVIDENCE, CORPUS_NOT_INDEXED, SEARCH_ERROR다. 뒤의 둘은 아무것도 확인하지 않았다는 뜻이다.
RegulationHit에는 노션 필드 외에 document_key, section, page, source_url, acquisition, effective_from, effective_until을 더했다.
search_regulations는 선택 인자 review_date를 받아 그날 시행 중인 법령 버전만 찾는다. 이 봉투와 추가 필드는 Agent 담당의 확인이 필요한 제안이다.
v0.1 search_rules, search_cases, get_evidence는 2026-10-21에 지웠다. 아래 "정리한 v0.1 기반 도구"를 본다.

rag-mcp Resource는 모델이 아니라 앱(근거 패널)이 고르는 읽기 전용 원문이다. 검색 도구는 발췌를 돌려주고 Resource는 적재한 원문 전체를 돌려준다.

| Resource | 반환 | 비고 |
| --- | --- | --- |
| regulation://corpus | documents: document_version_id, document_key, title, 버전, 시행일, 조각 수 | 원문을 읽을 id 목록. /health/corpus와 같은 목록 |
| regulation://documents/{document_version_id} | 제목, 버전, source_url, 시행일과 sections(section, page, text) 목록 | 적재 순서 그대로의 원문. 없는 id와 DB 장애는 ResourceError |

화면의 근거 항목에서 "전체 원문 보기"를 누르면 GET /api/v1/reference-documents?key={document_key}&version={버전}을 부른다.
Spring은 Agent의 GET /internal/regulations/document로 넘기고, Agent는 regulation://corpus에서 같은 document_key와 버전의 id를 찾아
regulation://documents/{id}를 읽는다. 같은 법령의 다른 버전으로 바꿔 보여 주지 않는다.
없는 문서나 버전은 404 REGULATION_DOCUMENT_NOT_FOUND, rag-mcp 장애는 502 REGULATION_SOURCE_UNAVAILABLE이다.
GMP와 규제정보 API는 검색 인자가 없어 최대 20쪽(2,000건)을 훑어 이름으로 거르고, 다 못 훑으면 incomplete를 반환한다.

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
출처 원문 전체는 도구가 아니라 Resource regulation://documents/{document_version_id}로 읽는다.

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
| 승인 대기 | REST 초안은 processing/completed/failed만 제시하고 Graph는 interrupt 대기 | 실행 상태와 HITL 상태 분리, pending 화면과 중간 결과 계약 보완. 2026-10-20 HITL 재설계로 HITL 상태는 violation 단위 결정(kept/rewritten/rewritten_manual)과 review 전체 최종 결정(승인/반려/원본재업로드요청)으로 나뉨 — 단일 hitl_status 필드로 되돌리지 않음. REST 응답 필드 확정은 별도 계약 PR |
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

## 초기 v0.1 기반과의 연결

아래 내용은 실행 가능한 개발 기반 v0.1이다. 위 7개 목표 도구와 /api/v1 업무 API의 구현 완료를 뜻하지 않는다.
후속 계약 PR에서 packages/shared, contracts, MCP 도구와 소비자 및 테스트를 함께 전환한다.
소스와 잠금 파일은 이번 문서 보완에서 변경하지 않는다.

## 초기 v0.1 호환 HTTP와 신규 실행 경로

| 엔드포인트 | 현재 의미 |
| --- | --- |
| GET /api/dev/status | Spring Boot에서 DB와 Agent, MCP 연결 상태 집계 |
| GET /internal/bootstrap | 내부 토큰 검증 후 Spring Boot와 DB 연결 확인 |
| GET Agent /health/live | Agent 프로세스 확인 |
| GET Agent /health/ready | Agent DB 연결 확인 |
| GET Agent /internal/environment | 내부 토큰 검증 후 MCP 목록과 DB 확인 |
| POST Agent /internal/runs | 신규 MVP의 scoped RunRequest 필요, 인증 후 빈 입력은 422 |

내부 API는 Authorization: Bearer <INTERNAL_SERVICE_TOKEN>을 요구한다.
health 경로는 자격증명을 반환하지 않는다. 현재 로그인과 사용자 API는 /api/v1/users 아래에 있다.

## 정리한 v0.1 기반 도구

HTTP 주소는 business-mcp:8002/mcp와 rag-mcp:8003/mcp다.
호스트에서는 각각 127.0.0.1을 사용한다. 인증 방식은 개발용 정적 Bearer 토큰이다.

2026-10-21에 NOT_IMPLEMENTED만 돌려주던 v0.1 기반 도구 4개와 그 계약을 지웠다. 호출하던 곳은 없었다.

| 지운 도구 | 대신 쓰는 것 |
| --- | --- |
| rag.search_rules | rag.search_regulations, rag.lookup_claim_rules |
| rag.search_cases | rag.search_precedents |
| rag.get_evidence | Resource regulation://documents/{document_version_id} |
| business.get_product_context | 없음. 제품 정보는 Spring이 RunRequest.product로 넘긴다 |

함께 지운 것은 packages/shared의 contracts.py(SearchRulesInput, SearchRulesResult, ScaffoldResult)와
contracts/search-rules.input.schema.json, contracts/search-rules.output.schema.json이다.
Pydantic 계약은 packages/shared의 rag_contracts.py와 review_contracts.py가 원본이다.
남은 JSON Schema는 scripts/export_contracts.py로 갱신한다.

지운 도구에 있던 규칙 중 지금 도구에도 그대로 적용하는 것:

- tenant_id를 모델 입력에 추가해서 권한을 결정하지 않는다.
- 사례를 1차 법령 근거 목록에 섞지 않는다.
- 근거의 page는 근거 문서의 페이지다. 광고 위치 좌표와 혼용하지 않는다.


## 입력과 오류 확장 제안

광고 SourceBlock과 페이지 및 좌표는 Agent 입력 담당, 증빙 EvidenceStructured는 Business MCP,
RegulationHit와 PrecedentHit는 RAG 담당의 출력이다. 광고 위치와 근거 문서 위치를 구분한다.

prepare_input, SourceBlock, Claim과 Finding은 이전 기반의 확장 제안이다.
노션의 7개 도구와 별개로 표기하고 추가 필요가 있을 때만 별도 계약으로 채택한다.
기존 문서의 INPUT_REJECTED, INPUT_UNREADABLE, INPUT_EXPIRED, PROVIDER_UNAVAILABLE,
PROVIDER_CONFIGURATION_ERROR, EVIDENCE_MISSING, LOOKUP_FAILED, NOT_FOUND와 VERSION_CONFLICT도 후속 오류 코드 제안이다.
현재 외부 조회의 status와 공개 HTTP 오류를 이 코드로 이미 통일했다고 표시하지 않는다.

검토 이력 조회와 삭제는 Backend JDBC 경로다. 과금과 크레딧 원장, 잔액 확인과 사용량 이벤트는
Agent 및 MCP 밖의 별도 Application 서비스가 책임진다. 중도 실패와 HITL 대기의 과금 시점은 미확정이다.


## 2026-10-20 단건 최소 기능 계약 추가

`feat/non-vector-mvp`에서 다음 HTTP를 실제 연결했다. 기존 v0.1 검색 도구는 호환성을 위해 그대로 남겨 둔다.
이것이 노션의 목표 업무 기능 전체를 구현했다는 뜻은 아니다.

- POST /api/v1/users/signup: email, password, name, tenantName. 새 기업 관리자만 생성하며 임의 tenantId와 role은 받지 않는다.
- POST /api/v1/users/login, GET /api/v1/users/me, DELETE /api/v1/users/logout: 서버 JWT와 세션, 기업 및 역할.
- POST /api/v1/users/refresh: Access Token 재발급. 아래 인증 토큰 절을 따른다.

### 인증 토큰 (feat/jwt-refresh-token)

- signup, login, refresh 응답 본문: `user`, `tenant`, `accessToken`, `expiresIn`(초). Access JWT는 기본 15분이며 클레임은 sub, sid(세션), tenantId, role이다.
- 같은 응답의 `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/users`, 기본 14일. 본문에는 Refresh Token을 넣지 않는다.
- DB에는 Refresh Token의 SHA-256 해시만 `app.refresh_tokens`에 저장한다. 재발급할 때마다 새 토큰으로 회전하고 세션 만료를 연장한다.
- refresh 401의 message: `REFRESH_TOKEN_MISSING`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REUSED`(이미 회전된 토큰 재사용 → 세션 전체 폐기), `REFRESH_TOKEN_ROTATED`(30초 안의 탭 간 동시 재발급 → 쿠키를 유지하므로 한 번 재시도).
- login과 signup은 요청에 기존 Refresh 쿠키가 있으면 그 세션을 먼저 폐기한다. Access JWT 만료는 clock skew 없이 적용한다.
- logout은 Access Token이 만료돼도 호출할 수 있다. 쿠키와 유효한 Bearer의 세션을 모두 폐기하고 쿠키를 지운다. 세션이 지워지면 그 세션의 Access JWT도 즉시 401이다.
- signup, login, refresh, logout은 Authorization 헤더를 검사하지 않는다. 그 외 API에서 401을 받으면 Frontend가 refresh 후 한 번 재시도한다.
- POST /api/v1/users/team: 관리자만 같은 기업의 author 또는 reviewer 생성.
- POST /api/v1/reviews: multipart metadata(JSON 문자열), files(광고 PDF/이미지 배열), evidence(선택 증빙 배열).
- metadata: product, media, evidence의 docType 목록. 직접 광고 카피 입력은 받지 않는다.
- GET /api/v1/reviews 및 /{reviewId}: 기업 범위의 서버 저장 결과. 현재 200건 한도.
- POST /api/v1/reviews/{reviewId}/re-review: 같은 multipart 형식, 새 원본 필수, 새 버전과 별도 승인 이력.
- POST /api/v1/reviews/{reviewId}/decision: versionNo, state, comment. 모든 결정에 의견 필수, 서버 역할 및 최신 버전 검증.
- DELETE /api/v1/reviews 및 /{reviewId}: 작성자 또는 관리자 범위 삭제.
- GET /api/v1/reference-sources: 9개 출처와 수집 경로, rag-mcp /health/corpus에서 받은 적재 문서 목록(corpus)과 retrievalStatus(indexed, not_indexed, unavailable).

반환은 제공된 프론트의 review/versions 형식으로 고정했다. public ID는 camelCase이고 Graph 내부와는 명시적으로 매핑한다.
문구 결과 findings와 사진 결과 visualFindings, 두 pipelineStatus를 따로 보존한다.
result.tiles는 분석에 쓴 내용 구간의 좌표 목록이다(ResultTile, contracts/mvp-result-tile.schema.json). id, index, fileId, fileName, page와 원본 페이지 기준 0~1 region, 구간 이름 title(없으면 null)을 가지며 픽셀은 저장하지 않는다.
findings와 visualFindings의 각 항목은 대표 구간 하나의 tileId를 가진다. 구간은 겹치지 않지만, 예전 결과처럼 겹치는 타일이 있으면 박스를 온전히 담는 타일 중 중심이 가장 가까운 타일 하나에만 속하고, 대표 구간을 정할 수 없으면 null이다.
result.evidence는 documents(문서별 id, name, docType, pages, scannedPages, characters, text), failures(documentId, name, code, page), summaries(문서별 시험 요약), claimMatches(findingId, documentId, documentName, verdict, reason, needed)를 가진다. verdict는 supports, different_test, condition_mismatch, not_covered 중 하나이고, 실증 대조 check의 status는 evidence_supports, evidence_mismatch, evidence_conditions, evidence_not_covering, evidence_unread가 된다.
문구 finding의 rewrites에는 규칙 검사와 모델 검증을 통과한 수정안만 들어간다. 통과한 수정안이 없으면 rewrites는 비고 rewriteNote에 마지막 실패 이유를 남긴다.
이 필드가 추가되기 전에 저장된 결과에는 tiles와 tileId가 없으므로 소비자는 두 필드가 없는 경우를 기존 표시로 처리한다.
LangSmith trace가 켜진 실행은 result.traceId(LangSmith run id)를 가진다. 꺼져 있으면 필드가 없다.
그래프 실행이 시작된 뒤 실패한 /internal/runs(422 INPUT_UNREADABLE_OR_EXPIRED, 500 RUN_FAILED)는 같은 id를 X-Trace-Id 응답 헤더로 돌려준다.
파일을 읽다 그래프 전에 실패하면 LangSmith run이 없으므로 헤더도 없다.
Spring 큐 소비자는 이 값을 app.review_jobs.last_trace_id에 last_error와 함께 저장하고, 작업이 완료되면 둘 다 비운다.
retrievalStatus는 connected, not_indexed, error, skipped(문구 없음) 중 하나이고, 이전 결과에는 not_connected가 남아 있다. legalStatus는 not_evaluated로 유지한다.
basis와 standardsSnapshot은 rag-mcp 검색 결과로만 채우며, finding.legalSearch(basis_found, no_match, failed, not_searched)로 문구별 상태를 남긴다.
현재 PDF 리포트는 DB 결과를 읽는 프론트의 인쇄/PDF 저장이다. 서버 PDF 바이너리 엔드포인트와 배치 SSE, 배치는 후속이다.

### 검토 진행 SSE (feat/review-progress-sse)

GET /api/v1/reviews/{reviewId}/events, `text/event-stream`. 일반 JSON API와 같은 Bearer JWT를 받고 기업 범위 밖이면 404다.
브라우저 EventSource는 헤더를 못 보내므로 프론트는 fetch 스트림으로 읽는다. 토큰을 주소에 넣지 않는다.

- 첫 이벤트는 항상 `snapshot`(GET /api/v1/reviews/{id}와 같은 본문)이다. 최신 버전이 queued나 analyzing이 아니면 snapshot만 보내고 닫는다.
- 이후 이벤트의 data는 type, reviewId, versionNo, at과 아래 필드를 가진다. 각 이벤트에는 증가하는 id가 있고, 재접속 때 `Last-Event-ID`를 보내면 그 뒤의 최근 이벤트(검토별 50개)를 다시 보낸다.

| event | 보내는 곳 | 추가 필드 |
| --- | --- | --- |
| started | Spring, 실행 시작 | |
| step_done | Agent, 단계 완료(0 준비, 1 문구, 2 사진, 3 법령과 공식 조회, 4 결과 저장) | step |
| tool_started, tool_finished | Agent, 법령 검색(legal_basis)과 business-mcp 도구 | tool, ok(끝날 때. 어댑터의 api_error와 미설정은 false) |
| retry_scheduled | Spring 큐, 일시 실패 후 재시도 예약 | attempt, delaySeconds |
| completed | Spring, 결과와 크레딧 정산 저장 후. 결과가 곧 리포트다. 스트림을 닫는다 | |
| failed | Spring, 최종 실패. 스트림을 닫는다 | error |

15초마다 `:ping` 주석을 보낸다. Agent는 POST /internal/reviews/{id}/events(내부 토큰과 X-Run-Token)로 step_done, tool_started, tool_finished만 보낼 수 있다.
옛 /internal/reviews/{id}/progress는 2026-10-22에 지웠다(호출하는 Agent 없음). 저장된 결과가 원본이며 이벤트는 보조 신호다.
구독자와 재전송 버퍼는 API 프로세스 메모리에 있다. API를 여러 대로 늘리면 Redis pub/sub 같은 공유 채널이 필요하다.

2026-10-22 승인을 없애면서 approval_required, resume_completed, resume_failed 이벤트도 없앴다.

내부 /internal/runs의 원본 모델은 packages/shared/src/capstone_shared/review_contracts.py,
JSON Schema는 contracts/mvp-*.schema.json이다. runToken은 서버 전용이고 모델 입력 및 checkpoint에 넣지 않는다.
Business MCP의 structure_evidence는 실행 문맥을 서버 HTTP 헤더에서 받는 최소 구현이다.
verify_functional_approval와 verify_patent는 실제 HTTP adapter와 설정/오류 처리를 추가했지만 승인키를 사용한 정상 응답 검증은 미완료다.
MFDS_REPORT_URL은 공식 getRptPrdlstInq01 경로를 기본값으로 제공한다. 인증 실패와 페이지 조회 한도를 제품 미조회로 바꾸지 않는다.
match_product_claims와 match_patent_claims, 두 목표 RAG 검색은 이번 최소 기능에서 구현하지 않았다.


### 문구별 위반과 결정 (feat/violation-tables, 2026-10-22 검토 전체 승인 제거)

2026-10-22 팀 결정으로 검토 전체 승인(승인, 수정 요청, 반려), 검토 완료 상태, 재검토(같은 검토에 새 버전 올리기)를 없앴다.
분석이 끝나면 결과가 바로 리포트이고, 사람은 문구마다 원문 유지, 수정안 채택, 직접 수정, 다시쓰기를 그때그때 한다.
`POST /api/v1/reviews/{id}/decision`, `POST /api/v1/reviews/{id}/re-review`는 없다(404). 수정한 광고는 새 검토로 올린다.

저장 원본과 규칙은 docs/04-data.md의 문구별 위반과 HITL 저장 규칙 절을 따른다. 모든 경로는 JWT의 tenantId로 review를 찾으므로 다른 기업의 review와 violation은 404다.
review id와 violation id가 UUID가 아니면 400 `INVALID_REQUEST`, violation이 해당 review에 없으면 404 `VIOLATION_NOT_FOUND`다.

GET /api/v1/reviews/{reviewId} (POST 생성과 문구 결정 API의 응답도 같은 형태) 기존 review 문서에 두 필드를 더한다.

- `hitl`: violationCount, undecidedCount. 리포트 하단 "확인 문구 N건 중 M건 결정" 진행률이며, 모두 결정해도 따로 완료 상태는 없다.
- `violations[]`: 최신 버전 finding 순서. id, findingId(JSONB finding id), textExcerpt, severity, rewriteSuggestion, rewriteAttemptCount,
  humanRetryCount, humanRetryLimit, finalResolution(kept, rewritten, rewritten_manual 또는 null), finalText, finalTextValidated, decidedBy, decidedByName, decidedAt,
  rewrites[](attemptNo, text, requestedByHuman, retryNote, createdAt).
- 최신 버전 `result.findings[]`의 각 항목에도 `violationId`가 있다.

GET /api/v1/reviews 목록의 각 review에도 같은 `hitl`을 더한다(violations는 싣지 않는다).
새로 만든 버전 문서에는 `approval`이 없다. 승인 제거 전에 저장된 문서의 `approval`과 app.reviews.hitl_* 컬럼은 남아 있지만 읽거나 쓰지 않는다.

POST /api/v1/reviews/{reviewId}/violations/{violationId}/decision: 작성자, reviewer, admin. 최신 버전이 done이어야 한다(409 `VERSION_NOT_READY`).
결정은 잠기지 않아 언제든 다시 보내 바꿀 수 있다.

- `{"resolution":"kept"}`: finalText=원문.
- `{"resolution":"rewritten","attemptNo":2}`: 해당 시도의 문안. attemptNo가 없으면 최신 rewriteSuggestion. 제안이 없으면 409 `NO_REWRITE_SUGGESTION`, 없는 시도는 404 `REWRITE_ATTEMPT_NOT_FOUND`.
- kept와 rewritten에 finalText를 보내면 400 `FINAL_TEXT_NOT_ALLOWED`. 둘 다 finalTextValidated=true로 저장한다.
- `{"resolution":"rewritten_manual","finalText":"..."}`: 사람이 쓴 문구는 재검증을 통과해야 저장한다. finalText가 비면 400 `FINAL_TEXT_REQUIRED`.
  Backend가 Agent 내부 `/internal/rewrites/verify`를 호출해 재검증한다. 통과(`passed=true`)하면 finalTextValidated=true로 저장한다.
  통과하지 못하면 409(agent의 reasoning을 메시지로 쓰며, reasoning이 비어 있으면 `MANUAL_TEXT_FAILED_VERIFICATION`)이고 저장하지 않는다.
  모델 키가 없으면 503 `REWRITE_MODEL_NOT_CONFIGURED`, Agent 연결 실패는 503 `AGENT_UNAVAILABLE`, 그 외 호출 실패는 502 `REWRITE_VERIFICATION_FAILED`다.

POST /api/v1/reviews/{reviewId}/violations/{violationId}/rewrite-requests `{"retryNote":"..."}`(선택, 1000자 이하): 작성자, reviewer, admin. 같은 상태 조건.
사람 요청 수가 humanRetryLimit에 도달하면 409 `HUMAN_REWRITE_LIMIT_REACHED`이며 Agent를 호출하지 않는다.
성공하면 requested_by_human=true인 새 시도가 추가되고 rewriteSuggestion이 바뀐다. 기존 문구 결정은 유지한다.
Agent 모델 키가 없으면 503 `REWRITE_MODEL_NOT_CONFIGURED`, Agent 연결 실패는 503 `AGENT_UNAVAILABLE`, 생성 실패는 502 `REWRITE_FAILED`이며 이때는 저장하지 않는다.

검토 전체 결정 API와 결정 후 Agent 재개 작업(app.review_jobs kind=resume, /internal/runs/resume 호출)은 2026-10-22에 없앴다.
V15가 끝나지 않은 재개 작업을 `failed`(`REVIEW_APPROVAL_REMOVED`)로 닫았고, 브로커에 남은 재개 메시지는 실행하지 않고 DLQ로 보낸다.
Agent 그래프의 승인 대기(interrupt)와 /internal/runs/resume은 ai_agent에 남아 있지만 Backend는 더 이상 부르지 않는다. 승인 노드 뒤 report 노드는 하는 일이 없어 기능 영향은 없다.

Agent 내부 POST /internal/rewrites: 입력 contracts/mvp-agent-rewrite.input.schema.json(reviewId, tenantId, phrase, reason, claimType, previousRewrites, retryNote),
출력 mvp-agent-rewrite.output.schema.json(text). 내부 토큰 필수. 모델 키가 없으면 503 `TEXT_MODEL_NOT_CONFIGURED`, 공급자 실패는 502.
한도 판정과 저장은 Backend가 하고 Agent는 문안만 만든다. 법 적합을 판정하지 않는다.

Agent 내부 POST /internal/rewrites/verify: 입력(reviewId, tenantId, text, reason), 출력(passed, reasoning). 내부 토큰 필수.
사람이 직접 쓴 rewritten_manual 문구가 법 적합 재검증을 통과하는지만 확인하며, 위 decision 엔드포인트에서만 호출한다.

프론트(ViolationCard.vue/RevisionOptions.vue, src/shared/stores/reviews.js)는 위 decision/rewrite-requests API에 실제로 연동돼 있다.

## 공식 API 명세 재확인

2026-10-20 공공데이터포털의 inline Swagger JSON을 직접 읽고 contracts/external에 저장했다.
보고품목은 getRptPrdlstInq01이며 getRptEntpInq01은 업체 조회이므로 구분한다.
cosmetic_report_seq, item_name 필터와 COSMETIC_REPORT_SEQ, DEPT_RECEIPT_NO를 정확히 맞추고
EFFECT_YN1~3, SPF와 PA 및 취하승인여부를 원본 필드로 반환한다. 기능은 Y로 명시된 경우만 표시한다.
최대 3페이지 조회로 전체 결과를 확인하지 못하면 ambiguous를 반환한다. 취하 품목은 cancelled다.
정상 실응답 검증 완료 여부는 docs/07-validation.md를 따르며 명세 확보를 실응답 검증으로 표현하지 않는다.

/api/v1/reference-sources는 기존 sources와 함께 apis 배열도 반환한다. 문서 9건과 API 4종의 역할을 섞지 않는다.
GMP와 성분 규제정보는 참고 데이터이며 현재 Agent 업무 도구로 추가 등록하지 않았다.


### 접수 모달과 실패 시 크레딧 처리 (2026-10-21)

크레딧 차감 확인을 누르면 네트워크 응답 전에 진행 모달을 연다. 접수 전에는 잔액 확인과 파일 업로드 상태를 표시하고 분석 5단계는 모두 대기로 둔다. POST의 202 응답으로 reviewId를 받으면 기존 SSE를 구독하며, 상세 화면 이동과 SSE 응답을 기다리지 않고 모달을 유지한다. 접수 중 모달을 닫은 선택도 상세 화면으로 이어 간다. 접수 실패 시 입력과 선택 파일을 유지하며 재시도할 수 있다.

접수 실패는 review와 크레딧 예약을 같은 트랜잭션에서 롤백한다. 접수 후 최종 분석 실패는 예약분을 한 번만 반환한다. HTTP 200이어도 result.pipelineStatus.text와 visual이 모두 complete인 경우에만 차감을 확정한다. partial, not_configured, needs_input 또는 상태 누락은 결과를 보존하면서 예약분을 반환한다. 이 결과도 큐에서 처리 완료로 인정하여 다시 과금하거나 재실행하지 않는다. 분석 결과와 정상 완료 시 정산은 같은 트랜잭션에 저장한다. SSE 연결 실패 자체는 분석 실패가 아니며 재접속 또는 폴링으로 실제 결과를 확인한다.

2026-10-22 복구 보강: 최종 실패 요청은 `app.review_failure_requests`에 먼저 커밋한다.
실패 상태 저장과 예약 반환은 같은 트랜잭션에서 실행하고 둘 다 커밋한 뒤 `failed`를 보낸다.
정산 오류는 요청을 남겨 60초마다 재처리한다. DB 전체 장애로 요청 기록도 실패하면,
큐 작업이 없는 예약 중 `analyzing`이 7분 이상 또는 `queued`가 30분 이상 갱신되지 않은 건을 복구한다.
큐 작업이 있는 건은 기존 큐 재시도와 정체 작업 복구를 따른다. 이미 `done`인 결과는 늦게 온 실패가 덮어쓰지 않는다.
DB 장애 중에는 반환이 지연될 수 있으며, 즉시 반환을 보장하는 계약은 아니다.

2026-10-22 원본 조회 보강: Redis 저장과 정리는 보조 작업이며 캐시 장애만으로 접수를 거절하지 않는다.
캐시는 접수 커밋 후 채우고, Agent용 원본 조회는 실행 토큰 및 review와 version 범위를 확인한 뒤
캐시가 없거나 읽을 수 없으면 `review_assets.bytes`를 반환한다. DB 원본도 없는 예전 파일만 410을 유지한다.
캐시 TTL 만료 후에도 DB 원본과 유효한 실행 토큰이 남아 있으면 조회할 수 있다. 토큰 수명과 원본 보존 정책은 별도 변경하지 않는다.
