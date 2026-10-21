# 화장품 광고 검토 Backend

Java 21과 Spring Boot 업무 API다. 인증과 기업 권한, 업로드, 결과와 이력, 크레딧, 승인과 RabbitMQ 작업을 관리한다.
Python Agent와 Business MCP, RAG MCP는 형제 `ai_agent` 저장소에 있다. 이번 분리는 기존 동작을 유지한다.

## 저장소와 통합 실행

세 저장소를 같은 상위 폴더에 둔다. backend와 ai_agent의 main을 사용한다.
Frontend는 기존 main을 그대로 사용한다. backend의 `ai-agent.ref`에는 검증한 AI 커밋이 기록된다.

```sh
mkdir beauty-capstone
cd beauty-capstone
git clone --branch main https://github.com/SKALA-AI-Agent-Capstone/backend.git
git clone --branch main https://github.com/SKALA-AI-Agent-Capstone/ai_agent.git
git clone https://github.com/SKALA-AI-Agent-Capstone/frontend.git
cd ai_agent
git checkout $(cat ../backend/ai-agent.ref)
cd ../backend
python3 scripts/bootstrap.py
docker compose --profile web up --build -d
```

Docker가 실행 중이어야 한다. 기본 화면은 http://localhost:5173 이다.
기존 개발자는 `.env`와 data, Docker 볼륨을 그대로 사용한다. DB 초기화, 비밀번호 재생성과 원본 파일 복사는 하지 않는다.
AI 코드 위치가 다르면 `.env`의 `AI_AGENT_DIR`를 지정한다. 기본은 ../ai_agent다.
모델과 공식 API 키는 Git에 넣지 않고 backend/.env에서 Compose가 필요한 서비스에만 전달한다.

`rag-ingest`는 일회성 작업이므로 전체 Compose에 --wait를 쓰는 대신 장기 실행 서비스만 지정한다.

```sh
docker compose up --build -d --wait --wait-timeout 240 postgres redis-cache rabbitmq api business-mcp rag-mcp agent
```

위 실행은 실제 키가 설정되면 기존과 같이 공개 근거 수집과 임베딩 API를 사용할 수 있다.

## 개발과 검사

```sh
# backend: 업무 API만 개발
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f services/api/pom.xml verify
python3 scripts/run_api.py

# AI: 별도 터미널의 ai_agent에서
uv sync --all-packages --frozen
uv run --all-packages --frozen pytest
uv run --all-packages --frozen python scripts/export_contracts.py --check

# 전체 합성 검사: 모델 키를 비운 격리 환경에서만 실행
# ai_agent에서 실행, 실제 키를 사용하는 개발 서비스와 구분
uv run --all-packages --frozen --env-file ../backend/.env python scripts/smoke.py --include-queue
uv run --all-packages --frozen python scripts/mvp_smoke.py --require-queue
```

통합 검사는 테스트 계정을 만들고 합성 광고를 처리한다. 실제 개발 환경에서 무심코 실행하지 않는다.
검증 계정이 필요하면 ai_agent/scripts/setup_demo.py를 사용한다. 생성 비밀번호는 Git에서 제외된다.
Backend의 Python 파일은 환경 생성과 Java 실행, 개발환경 회귀 검사에 사용하는 도구이며 AI 런타임 코드는 없다.

## 협업 기준

- 요구사항: [노션 기획서](https://app.notion.com/p/Agent-3fe616ab8fec80bfb92df115c66aa83d)
- 데이터: [노션 ERD](https://app.notion.com/p/Data-ERD-App-State-Vector-DB-3ff616ab8fec81c7b57ac75358ced570)
- [아키텍처](docs/01-architecture.md), [개발 환경](docs/02-development.md), [계약](docs/03-contracts.md)
- [프로젝트 메모리](docs/08-project-memory.md), [분업](docs/10-work-breakdown.md), [검증 기록](docs/07-validation.md)

공통 문서는 backend/docs에, Python 계약 원본과 생성 JSON Schema는 ai_agent에 둔다.
공개 출처 목록은 ai_agent/resources가 원본이며 Spring의 resources는 배포용 스냅샷이다.
AI의 `scripts/check_backend_registry.py --backend ../backend`로 일치를 검사한다.
한쪽 저장소의 PR만으로 호환 버전이 달라지지 않도록 연결 변경과 AI 고정 커밋을 함께 검토한다.
후속 작업은 각 저장소의 main에서 기능 브랜치를 만들고 PR로 검토한다.
