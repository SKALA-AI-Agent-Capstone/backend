# 하네스 평가

하네스(프롬프트, 규칙 sweep, 규칙표, 입력 한도, 증빙 대조 규칙)를 바꾸면 평가셋을 다시 돌려 전후 결과를 날짜별 문서에 남긴다.
점수만 보지 않고 놓친 문구와 오탐 목록을 같이 본다. 평가셋은 만든 날짜로 구분하고, 날짜가 다른 평가셋의 점수는 합치지 않는다.

## 한눈에 보기

| 평가셋 | 무엇을 보나 | 사례 | 최신 결과 |
| --- | --- | --- | --- |
| [2026-10-21](2026-10-21.md) | 광고 문구 줄마다 확인할 문구를 찾는지, 근거 조항이 맞는지 | 합성 광고 20건(정답 문구 30개) | 탐지율 98.9%, 근거 조항 일치 100% |
| [2026-10-22](2026-10-22.md) | 광고 문구와 증빙을 넣었을 때 문구별 검토 결과(수정 권고, 증빙 필요 등)가 맞는지 | 24건(실제 광고와 보고서 포함) | 충족률 66.7%(24건 중 16건). 증빙 필요 11건 중 3건만 충족 |

## 파일 위치

평가 코드와 데이터는 ai_agent 저장소에 있고, 이 폴더의 문서만 backend에 있다(공통 문서는 backend에서 한 번만 관리한다).
새 평가셋을 만들면 ai_agent의 evals/<날짜>/와 이 폴더의 <날짜>.md를 추가하고 위 표에 한 줄을 더한다.

| 내용 | ai_agent 경로 |
| --- | --- |
| 평가셋 | evals/2026-10-21/harness_cases.json, evals/2026-10-22/harness_cases.json |
| 2026-10-22 검토표와 증빙 자료 목록 | evals/2026-10-22/harness_cases.md, evals/2026-10-22/fixtures/README.md |
| 실행기 | scripts/evaluate_harness.py(2026-10-21), scripts/evaluate_actions.py(2026-10-22) |
| 채점 | scripts/evaluation.py(2026-10-21), scripts/action_evaluation.py(2026-10-22) |
| 채점 테스트(모델 없이 실행) | tests/test_evaluation.py, tests/test_action_evaluation.py |
| 실행 원본 결과 | reports/eval (Git 제외) |

두 실행기는 ai_agent 폴더에서 실행하고, ai_agent에 .env가 없으면 옆 backend/.env를 읽는다. rag-mcp가 떠 있고 OPENAI_API_KEY가 있어야 한다.
모델 결과는 실행마다 달라서 3회 반복해 기록한다. LangSmith 추적이 켜져 있으면 모델 호출이 trace로 남는다.
긴 광고를 구간으로 나눌 때 앞, 중간, 끝의 문구를 놓치지 않는지는 별도 회귀 세트(ai_agent의 tests/data/text_review_eval.json,
scripts/evaluate_text_review.py)가 확인한다.
