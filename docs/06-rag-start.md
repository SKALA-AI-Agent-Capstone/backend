# RAG 담당 시작점

## 현재 제공된 것

- services/rag-mcp의 실행 가능한 FastMCP 서버와 개발용 인증.
- search_rules, search_cases와 get_evidence의 입력 및 출력 경계.
- 공개 문서용 knowledge 테이블과 읽기 전용 DB 계정.
- 임베딩 모델을 아직 실행하지 않은 vector(1024) 저장 공간.
- 계약 테스트와 전체 연결 smoke script.

## 먼저 구현할 순서

1. 출처 목록을 작성하고 문서 개정일과 적용 범위를 확인한다.
2. PDF에서 표의 표현과 비고, 예외 조건을 함께 추출한다.
3. 문서 버전과 페이지를 보존한 조각을 만든다.
4. rag_ingest 역할로 본문과 메타데이터를 적재한다.
5. 선택한 모델로 임베딩을 생성하고 모델 리비전과 함께 저장한다.
6. rag_reader 역할로 키워드 및 벡터 검색을 구현한다.
7. search_rules와 get_evidence를 실제 검색에 연결한다.
8. 주장이 다른 평가 입력으로 누락, 조건 보존, 인용 정확도를 확인한다.

문서 수집과 임베딩 생성은 별도 배치로 실행한다. 광고 검토 요청마다 전체 문서를 재색인하지 않는다.
선택 의존성이 필요하면 uv sync --package capstone-rag-mcp --extra ingest를 사용한다.
이 명령은 모델 가중치를 자동 수집하는 파이프라인이 아니다.

## 인용과 판단

quote는 저장된 원문의 정확한 구절이다. 모델이 생성한 설명을 원문 필드에 넣지 않는다.
FOUND는 관련 근거 후보를 찾았다는 뜻이다. 검색 유사도는 위법 확률이나 승인 점수가 아니다.
자료가 없으면 NO_RELEVANT_EVIDENCE나 NEEDS_CONTEXT로 전달한다.
예외 조건, 표의 비고와 다른 별표 참조를 함께 제공한다.

## 자료 구분

최신 노션 FR-2와 NFR-11에 따라 rules와 cases를 collection_key로 분리한다.
법률과 고시, 식약처 안내서, 협회 해설서와 사례의 종류는 source_type으로 구분한다.
사례는 확보한 경우에만 보조 참고로 제공하고 출처 검증 상태와 경고를 반환한다.
공식 출처라는 속성과 법적 효력을 동일하게 취급하지 않는다.
사내 자료나 비공개 시험자료는 현재 공개 코퍼스에 적재하지 않는다.

특허 조회와 원문 대조는 최신 노션 FR-14, FR-15와 6장에 따라 business-mcp가 전담한다.
RAG 담당은 특허 기능을 중복 개발하지 않는다. 공통 출처 형식이 필요하면 계약만 합의한다.

## 독립 개발에 필요한 입력

Agent 담당에게 claim_id, claim_text, surrounding_text, product_type, review_date를 요청한다.
광고 이미지의 위치는 OCR 담당 데이터이며, RAG의 page는 근거 문서의 페이지다.
초기에는 합성 문장으로 검색 도구를 개발하고 Agent가 MCP로 호출하는 지점까지 통합한다.

## 완료 증거

- 기대한 문서나 항목이 상위 검색 결과에 들어오는지 확인한 평가표.
- 인용문이 저장된 원문에 실제 포함되는지 확인한 결과.
- 예외 조건과 구버전 문서가 섞이는 입력에 대한 검증.
- 근거 없음, 문맥 부족과 DB 장애 시 응답.
- Agent에서 실제 tools/call로 호출한 기록.

## 출처 시작점

- 식약처 관리 지침: https://www.mfds.go.kr/brd/m_1060/view.do?seq=15700
- 협회 광고자문 해설서: https://kcia.or.kr/ad/cs/notice.php?no=17039&type=view
- 실증 시험방법 안내: https://www.mfds.go.kr/law/board/boardDetail.do?brdId=data0011&menuKey=38&seq=15567
- 2026 정책설명회와 사례: https://www.mfds.go.kr/brd/m_641/view.do?seq=32476
- 임베딩 후보: https://huggingface.co/BAAI/bge-m3

위 링크의 실제 개정 여부와 파일 내용은 수집 담당자가 다시 확인한다. 아직 DB에 수집한 자료는 없다.
