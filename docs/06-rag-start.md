# RAG 담당 시작점

개발 목표는 [노션 MCP 명세](https://app.notion.com/p/MCP-business-mcp-rag-mcp-API-3fe616ab8fec818bb82efebe45ca502f)의
search_regulations와 search_precedents다. 기존 기반의 이름을 목표 API처럼 사용하지 않는다.
공통 인터페이스와 미확정 항목은 docs/03-contracts.md에 모았다.

## 맡을 입출력

| 도구 | 입력 | 출력 필드 | 검색 범위 |
| --- | --- | --- | --- |
| search_regulations | query_text, top_k=5 | source, version, scope, excerpt, similarity, trust_level=high | 컬렉션 A, 법령과 기준 |
| search_precedents | query_text, top_k=3 | source, excerpt, similarity, trust_level=low, verified=False | 컬렉션 B, 보조 사례 |

query_text는 Agent가 추출한 광고 주장과 필요한 문맥이다. RAG에 광고 PDF와 이미지 OCR을 맡기지 않는다.
excerpt는 저장된 원문 그대로 반환한다. similarity는 법 위반 확률이 아니다.
사례는 출처 미검증과 보조 참고 표시를 유지하고 법령 검색 결과에 섞지 않는다.
문서 전체를 보내거나 결론을 만드는 것이 아니라 관련 근거를 찾고 출처를 반환하는 역할이다.

## 지금 있는 개발 기반

- services/rag-mcp의 FastMCP 서버, 개발용 인증과 상태 확인.
- 실행 계약 v0.1의 search_rules, search_cases와 get_evidence. 세 도구 모두 업무 기능은 NOT_IMPLEMENTED다.
- PostgreSQL knowledge 스키마와 rag_reader 및 rag_ingest 권한 분리.
- 문서 버전과 청크, vector(1024) 빈 저장 공간.
- Pydantic 계약, JSON Schema와 테스트, 전체 서비스 연결 smoke.

현재 이름과 반환 타입은 노션 목표와 다르다. 도구의 이름만 바꾸거나 서로 같은 API라고 표시하지 않는다.
get_evidence는 기반의 확장 제안이며 노션의 필수 도구 2개가 아니다.

## 먼저 진행할 계약 PR

Agent 담당자와 새 두 도구의 입력 및 출력 모델을 packages/shared에 작성하고 contracts를 생성한다.
현재 consumers와 테스트의 호출도 같은 PR 또는 연결된 PR에서 전환한다.
노션에 없는 필드를 모두 추가할 때까지 검색 개발을 멈출 필요는 없다. 다음 추적 및 오류 정책만 명시한다.

| 결정 | 필요한 이유 |
| --- | --- |
| 기존 quote와 목표 excerpt, document_version과 version 매핑 | 같은 원문과 버전이 반환돼야 함 |
| document ID, source URL, page와 section 보존 | 리포트에서 원문 위치를 다시 확인할 수 있어야 함 |
| review_date와 시행일, corpus_version | 적용할 버전과 검색 재현성 보장 |
| 빈 결과와 DB 장애 및 미구현의 구분 | 빈 목록을 적합 판정으로 오해하지 않도록 함 |
| 기존 v0.1의 제거 또는 호환 기간 | Agent가 두 종류 도구를 같은 것으로 중복 호출하지 않도록 함 |

claim_id와 위치 연결은 Agent 문맥에서 유지할 수 있다. 새 도구가 필수 인자로 받는다고 임의로 추가하지 않는다.
노션의 출력 목록에 오류를 어떻게 전달할지 정하고, 장애를 정상적인 빈 목록으로 변환하지 않는다.

## 구현 순서

1. 공개 출처 목록, 발행기관과 개정 버전 및 적용 범위를 정리한다.
2. 법령과 기준은 rules, 확보한 사례는 cases로 분리한다.
3. PDF 본문과 표, 비고 및 예외 조건을 파싱하고 문서 버전과 페이지를 보존한다.
4. rag_ingest로 원문과 메타데이터를 적재한다. 기존 버전을 덮어쓰지 않는다.
5. 선택한 임베딩 모델로 청크를 벡터화한다. 현재 bge-m3와 1024차원은 준비값이며 확정 모델이 아니다.
6. rag_reader로 키워드와 벡터 검색을 구현하고 필터 및 순위 조정을 평가한다.
7. 내부 검색 결과를 위 두 노션 반환 타입으로 변환한다. 인용과 자료 역할을 검증한다.
8. Agent가 실제 MCP tools/call로 두 도구를 선택해 호출하도록 통합한다.

공개 문서 수집과 색인은 별도 배치다. 광고 검토 요청마다 전체 문서를 다시 색인하지 않는다.
고객 광고와 비공개 증빙은 공개 코퍼스에 넣지 않는다. 사내 지침은 기업 권한과 삭제 전파를 설계한 뒤 확장한다.
선택 의존성은 uv sync --package capstone-rag-mcp --extra ingest로 설치한다. 이 명령이 실제 수집을 실행하지는 않는다.

## 다른 담당자와 나눌 일

- Agent: 광고 파싱과 주장 구조화, 검색 질의 선택, 여러 도구 결과를 종합한 판단과 리라이트 및 HITL.
- Business MCP: 증빙 구조화, 제품 범위 대조, 식약처와 KIPRIS 조회 및 특허 관련성 대조.
- Backend: 제품과 파일 권한, 검토 이력과 버전, 결과 보존 및 사용자 API.
- RAG: 공개 문서 수집, 법령 및 사례 검색, 원문과 출처 및 버전, 검색 평가.

공개 규정이 스캔본이면 RAG 수집 배치가 OCR을 사용할 수 있다. 광고 OCR을 RAG가 담당한다는 뜻은 아니다.
특허 청구항 수집과 조회를 RAG에 중복 배정하지 않는다.

## 완료 증거

- 기대한 법령과 조건이 검색 결과에 포함되는지 확인한 평가표.
- excerpt가 저장 원문에 실제 포함되는지 확인한 결과.
- 예외 조건, 개정 전후 문서와 적용 범위를 혼동하지 않는 결과.
- 사례의 trust_level과 verified=False 및 보조 표시.
- 검색 결과 없음과 DB 장애 시 서로 다른 처리.
- Agent의 실제 두 MCP 도구 호출과 소비자 계약 검증.

## 출처 시작점

- 식약처 관리 지침: https://www.mfds.go.kr/brd/m_1060/view.do?seq=15700
- 협회 광고자문 해설서: https://kcia.or.kr/ad/cs/notice.php?no=17039&type=view
- 실증 시험방법 안내: https://www.mfds.go.kr/law/board/boardDetail.do?brdId=data0011&menuKey=38&seq=15567
- 2026 정책설명회와 사례: https://www.mfds.go.kr/brd/m_641/view.do?seq=32476
- 임베딩 후보: https://huggingface.co/BAAI/bge-m3

위 링크의 실제 개정 여부와 파일 내용은 수집 담당자가 다시 확인한다. 아직 DB에 수집한 자료는 없다.
