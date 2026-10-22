# 프로젝트 맥락과 기준

## 기준 문서

제품 요구사항의 원본은 [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)다.
2026-10-20에 기획서 FR, NFR, 기술 스택, 서비스 아키텍처와 연결된 REST, MCP, Graph 및 실행 시퀀스 초안을 다시 확인했다.
노션은 읽기만 했으며 수정하지 않았다. 첨부 아키텍처와 과거 대화가 다르면 최신 노션 본문을 우선한다.
이 저장소의 docs는 노션 요구사항을 구현 경계와 개발 절차로 옮긴 자료다.
노션에서 확정하지 않은 세부사항은 구현 제안 또는 미확정으로 표시한다.

## 서비스

화장품 기업의 마케팅 작성자와 법무 또는 허가 검토 담당자가 게시 전에 광고를 사전검토하는 B2B 서비스다.
브랜드와 대행사에 별도 입력 경로를 강제하지 않는다. B2G는 후속 확장 가능성이다.
AI 결과는 내부 검토 초안이며 협회 자문이나 정부 인증을 대체하지 않는다.

제품정보와 광고 및 증빙 입력 → Agent 광고 파싱과 주장 추출
→ 필요한 법령 검색과 증빙 및 공식 조회를 선택하는 loop → 리라이트 → HITL → 리포트와 이력.
승인된 광고를 수정하면 새 버전으로 재검토한다.

사용자 확정 범위는 광고 파일 입력 두 종류, PDF와 이미지다.
텍스트 PDF, 스캔 PDF, 혼합 PDF와 긴 이미지는 서버 내부 처리 분기이며 별도 입력 메뉴가 아니다.
직접 카피 입력 UI는 제공하지 않는다. 개발 중 합성 Claim과 SourceBlock을 쓰는 것은 내부 테스트 경로다.
입력 두 종류와 직접 카피 UI 제외는 사용자의 명시적 정정을 따른다. 노션 REST 초안의 ad_text는 추출된 서버 내부 텍스트로 해석하는 안이며, 파일 필드명은 Frontend와 Backend가 계약 PR에서 맞춘다.

## 노션 요구사항과 구현 위치

| 노션 항목 | 책임 | 현재 기반 |
| --- | --- | --- |
| FR-1, FR-5, FR-6 | Agent의 OCR, 판단, 리라이트, HITL | 두 분석 경로와 PostgreSQL HITL, 실제 모델 호출은 키 필요 |
| FR-2, NFR-11 | rag-mcp의 법령과 기준, 사례 검색 | 화장품법 코퍼스(법제처 API)는 적재·임베딩·검색까지 연결됨, 나머지 출처 적재는 후속 |
| FR-3, FR-4, FR-10 | business-mcp의 증빙 대조와 식약처 조회 | 증빙 문자 추출과 공식 조회 adapter. 실증(시험자료) 주장은 Agent(evidence.py)가 문서 요약과 광고 각주를 대조해 뒷받침함/다른 시험/조건 다름/증빙에 없음으로 판정(2026-10-21). business-mcp의 match_product_claims(기능성 보고 범위 대조)·match_patent_claims(특허 청구항 대조)는 여전히 미구현 |
| FR-14, FR-15 | business-mcp의 특허 조회와 원문 대조 | KIPRIS 조회 adapter, 청구항 원문 대조는 후속 |
| FR-7, NFR-4 | Backend, 큐와 Worker의 다건 처리 | 단건 검토는 RabbitMQ outbox(app.review_jobs)로 큐잉·재시도(최대 3회, 5s/30s)·DLQ까지 연결됨(REVIEW_QUEUE_ENABLED, Compose 기본값 true). 여러 광고를 한 번에 올리는 다건 업로드 API와 Agent 쪽 별도 Worker는 여전히 없음 |
| FR-8, NFR-1 | 단일 입력과 단건 응답, SSE | 단건 202 접수 후 검토별 SSE 진행 이벤트, 연결 실패 시 상태 폴링. 배치 SSE는 후속 |
| FR-9, FR-12, FR-13 | Backend의 이력 조회, 삭제와 버전 관리 | 실제 결과와 버전 및 승인 보존, 삭제 |
| FR-11 | 별도 과금 서비스와 크레딧 DB | app 스키마 내 Toss 테스트결제·크레딧 구매·리뷰당 차감 구현, 별도 과금 DB 분리는 후속 |
| NFR-3, NFR-5 | 원본 임시 Redis, 결과 영구 PostgreSQL | Redis와 PostgreSQL 실행환경 |
| NFR-9 | Backend의 인증, 기업과 역할 권한 | JWT와 DB 세션, 기업 및 역할 검사 |

현재 구현은 단건 MVP까지 확장됐다. 문구와 사진 예비검토를 제공하지만 법령 검색과 임베딩 및 과금은 구현하지 않았다. 공개 원문 수집과 기본 청킹 점검은 별도 스크립트로 제공한다.
실행 검증과 미구현 범위는 [검증 기록](07-validation.md)에 분리한다.

## 해석과 미확정 사항

- 노션은 단건 즉시 결과와 다건 202 처리를 요구하며, 단건도 내부 큐를 거치는 대안을 언급한다. 단건 큐 통일은 확정하지 않았다.
- 큐는 RabbitMQ로 확정됐다. Backend 쪽 단건 큐(outbox→publisher→consumer, 재시도와 DLQ)는 2026-10-21에 연결됐다. 다건 Backend → Agent API → RabbitMQ → Agent Worker로 이어지는 전체 시퀀스와 Worker 라이브러리는 후속이다.
- ERD v2는 reserve 후 judged_at에 charge하고 HITL 승인과 차감을 분리한다. 실패 전 release와 차감 후 오류 refund를 구분한다. 과금은 현재 미구현이며 상세 대응은 docs/04-data.md를 따른다.
- MCP 명세에 외부 API 요청과 응답 매핑 초안은 있다. 실제 호출로 확인할 필드와 번호 대응은 남아 있다. 설계 자체가 없는 상태로 설명하거나 API 컬럼의 추정 설명을 검증된 사실로 사용하지 않는다.
- FastMCP 버전, 임베딩 모델과 차원, Git 브랜치 규칙은 개발 기반의 선택 또는 제안이다. 노션의 확정 요구사항으로 인용하지 않는다.
- 노션 본문의 기술 선택 이유는 검증 대상 설명이다. 구현에 필요한 공식 문서와 실제 실행 결과를 함께 확인한다.
- 노션은 이미지 읽기에 Vision LLM 또는 OCR + LLM을 허용한다. docs/09-input-pipeline.md는 Agent의 광고 파싱과 Vision, Business MCP의 증빙 파싱을 구분한다. 로컬 파서와 OCR 제품의 선택은 구현 제안이다.
- 입력 준비 도구 prepare_input, 추출 데이터와 오류 계약은 신규 제안이며 현재 API나 MCP에 구현되어 있지 않다.
- 광고 원본을 폐기하면 과거 이력에서 이미지 재열람을 항상 제공할 수 없다. 승인 시 원본 확인과 재업로드 정책을 별도로 결정한다.

## 설계 안내

- 입력부터 서버 호출과 예외: docs/01-architecture.md, docs/09-input-pipeline.md.
- 역할별 산출물과 통합 순서: docs/10-work-breakdown.md.
- Git과 PR 운영안: docs/05-collaboration.md.
- 설명용 인터랙션은 합성 예시를 사용하는 흐름 탐색이며 실제 광고 검수 서비스 구현이나 외부 API 실호출이 아니다.

## 2026-10-20 상세 문서 대조

| 원본 | 확인한 내용 | 기반에 반영한 범위 |
| --- | --- | --- |
| [REST 초안](https://app.notion.com/p/Backend-REST-API-3ff616ab8fec81d3b650f8e3186bcf48) | reviews 접수, 상태, SSE, HITL, 이력과 과금 API | 현재 구현과 후속 엔드포인트 구분 |
| [MCP 외부 API 반영 초안](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-1-3ff616ab8fec804eba66e8a7b2bd99e8) | 증빙, 식약처와 특허 도구, search_regulations와 search_precedents | 기존 v0.1 계약과의 차이 및 계약 작업 기록 |
| [Graph 초안](https://app.notion.com/p/Agent-Graph-3ff616ab8fec8127ad6ed17eff1e81b0) | 입력 파싱 후 도구선택 loop, 리라이트, HITL, 리포트 | 고정 순차 호출로 오해하지 않도록 전체 흐름 보완 |
| [시퀀스 확정본](https://app.notion.com/p/Agent-RabbitMQ-3ff616ab8fec81c1a513df26b980b638) | Agent API가 다건을 발행하고 Worker가 실행 | RabbitMQ 환경, 역할과 연결 경계 반영 |

각 상세 문서의 필드명과 리턴 타입은 문서 자체에서도 초안 또는 확인 필요로 표시한다.
특허 getWordSearch의 현재 대조 범위는 명칭, 초록과 IPC이며 청구항 원문 대조는 별도 오퍼레이션 확보 후 확장한다.
API 정상 응답과 컬럼 의미를 실제 호출로 검증한 상태라고 표시하지 않는다.

## MCP와 API 등록부까지 다시 읽은 결과

[API 등록부](https://app.notion.com/p/AI-API-3ff616ab8fec8047bf0ec0ec5e508560)의 14개 항목은 /api/v1 경로다.
별도 REST 초안은 /api와 snake_case가 남아 있으므로 노션 전체가 일치한다고 설명하지 않는다.
MCP 두 문서의 7개 목표 도구와 반환 필드는 docs/03-contracts.md에 옮겨 비교했다.

Graph 하단은 Agent 단일 서비스를 명시한다. 시퀀스 이미지의 Agent API와 Agent Worker는 내부 역할이다.
이미지에는 접수와 발행 대기 기록 → 202 → 발행, 승인 대기 checkpoint 저장 → ACK → 결정 후 재발행이 명시돼 있다.
이 내용은 실제 Worker 구현 완료를 뜻하지 않는다. 목표 명세, 현재 기반 코드와 남은 불일치는 별도로 관리한다.
