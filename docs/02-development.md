# 개발환경

## 준비물

- Docker Engine이 실제 실행 중이어야 한다. Docker CLI 설치만으로는 충분하지 않다.
- Docker Compose 플러그인.
- 호스트 개발 시 Java 21과 Maven 3.9, Python 3.12와 uv 0.12.17, Node.js 24와 npm.
- frontend, backend를 같은 상위 디렉터리에 clone한다.

~~~sh
git clone --branch chore/dev-foundation https://github.com/SKALA-AI-Agent-Capstone/backend.git
git clone --branch chore/dev-foundation https://github.com/SKALA-AI-Agent-Capstone/frontend.git
cd backend
python3 scripts/bootstrap.py
docker compose config --quiet
docker compose up --build --wait
~~~

2026-10-20 확인 시 기반 PR은 두 저장소 모두 Draft이며 main에 미병합이다.
따라서 현재 시작 명령은 기반 브랜치를 지정한다. 기반 병합 후에는 main을 사용한다.

처음 실행하면 공개 근거 문서용 테이블까지 생성되지만 실제 문서 데이터는 없다.
SQL 초기화는 새 볼륨에만 실행된다. 기존 DB를 지우는 명령을 일반 실행 절차에 넣지 않는다.

## 실행 선택

~~~sh
# 데이터 서비스만 실행하고 담당 서비스는 호스트에서 개발
docker compose up -d --wait postgres redis-cache

# 기본 서비스 전체
docker compose up --build --wait

# 옆 디렉터리의 frontend까지 포함
docker compose --profile web up --build --wait

# 선택 다건 큐 환경 추가
docker compose --profile queue up -d --wait rabbitmq
~~~

web 프로필은 개발 서버의 컨테이너 실행이다. 코드 변경 후에는 이미지를 다시 빌드한다.
Vue를 빠르게 수정할 때는 DB와 백엔드를 컨테이너로 실행하고 frontend에서 npm run dev를 사용한다.
Redis 캐시는 비영구 설정이고 RabbitMQ는 별도 rabbitmq-data 볼륨을 사용한다. Worker와 파일 업로드 기능은 없다.
기존 .env가 있으면 bootstrap.py를 다시 실행해 누락된 설정만 추가한다. 기존 DB와 Redis 비밀번호는 유지한다.

## 담당별 호스트 실행

모든 담당자가 매번 전체 서비스를 켤 필요는 없다.

| 담당 | 최소 실행 범위 | 다른 역할과 연결할 때 |
| --- | --- | --- |
| Frontend | frontend의 npm run dev | 현재 상태 조회는 Backend 필요. 업무용 Mock은 미구현 |
| Backend | PostgreSQL과 Spring Boot | Agent가 없으면 현재 상태 응답에 미연결로 표시 |
| Agent | 전용 DB와 FastAPI | MCP 목록과 실제 도구 검증에는 두 MCP 필요 |
| Business MCP | MCP 서버 | 연결 확인과 업무 데이터에는 Backend 필요 |
| RAG MCP | PostgreSQL과 rag-mcp | Agent 연동 전 합성 검색 요청으로 개발 가능 |

Frontend는 src/views, src/stores와 src/api를 나눈 최소 구성이다.
업무 화면과 재사용 컴포넌트, 업무용 HTTP 명세 및 Mock은 기능 담당자가 작은 PR로 추가한다.
Queue는 선택 프로필이고 별도 과금 서비스는 현재 실행환경에 없다. 단건 통합부터 개발한다.

Python 환경은 backend 루트에서 한 번 설치한다.

~~~sh
uv sync --all-packages --frozen
uv run --all-packages --frozen --env-file .env business-mcp
uv run --all-packages --frozen --env-file .env rag-mcp
uv run --all-packages --frozen --env-file .env capstone-agent
~~~

각 실행 명령은 별도 터미널에서 사용한다. 포트가 같은 Compose 서비스를 동시에 실행하지 않는다.

Spring Boot:

~~~sh
python3 scripts/run_api.py
~~~

Vue:

~~~sh
cd ../frontend
npm ci
npm run dev
~~~

기본 프록시는 http://127.0.0.1:8080 이다.
프록시 변경이 필요하면 frontend/.env.example을 frontend/.env로 복사해 API_PROXY_TARGET을 수정한다.

## 검증

~~~sh
uv run --all-packages --frozen pytest
uv run --all-packages --frozen python scripts/export_contracts.py --check
mvn -f services/api/pom.xml test
docker compose config --quiet
uv run --all-packages --frozen --env-file .env python scripts/smoke.py
~~~

마지막 명령은 기본 서비스가 실제 실행 중일 때 사용한다.
연결과 토큰 인증, 미구현 응답, pgvector, DB 읽기 권한을 검사한다.

프론트엔드는 npm run build로 타입 검사와 빌드를 수행한다.

## 오류 해결

- Docker socket 오류: Docker Engine 또는 Docker Desktop 실행 상태를 확인한다.
- 포트 충돌: 기존 프로세스를 임의로 종료하지 말고 개발 포트와 .env의 호스트 DSN을 함께 변경한다.
- MCP 401: 같은 backend/.env의 INTERNAL_SERVICE_TOKEN으로 모든 내부 서비스를 실행하는지 확인한다.
- vector 확장 없음: 일반 postgres 이미지 대신 Compose의 pgvector 이미지를 사용한다.
- 비밀번호 변경 후 DB 접속 실패: 기존 볼륨의 역할 비밀번호는 .env 수정으로 자동 변경되지 않는다. DB 담당자와 역할 비밀번호를 맞춘다.
- GitHub push 실패: 본인 계정과 조직 저장소 쓰기 권한을 확인한다. 인증 토큰을 문서나 채팅에 붙여넣지 않는다.

## 전체 구성과 선택 실행

~~~sh
# Frontend를 포함한 전체 연결 점검
# frontend와 backend가 같은 상위 폴더에 있어야 한다.
docker compose --profile web --profile queue up --build --wait --wait-timeout 240
uv run --all-packages --frozen --env-file .env python scripts/smoke.py --include-queue
~~~

| 구성 요소 | 로컬 주소 또는 역할 | 연결하는 담당 |
| --- | --- | --- |
| Vue | http://localhost:5173 | Backend /api만 호출 |
| Spring Boot | http://127.0.0.1:8080 | 업무 DB와 Agent 내부 API |
| Agent API | http://127.0.0.1:8001 | 두 MCP, LLM과 향후 큐 publisher |
| Business MCP | http://127.0.0.1:8002/mcp | Backend 내부 API, 외부 조회 |
| RAG MCP | http://127.0.0.1:8003/mcp | knowledge 읽기 전용 |
| PostgreSQL | 127.0.0.1:55432 | app, agent와 knowledge 역할 분리 |
| Redis 캐시 | 127.0.0.1:56379 | Backend 원본 임시 보관, 연동은 후속 |
| RabbitMQ AMQP | 127.0.0.1:5672 | 향후 Agent API 발행과 Worker 수신 |
| RabbitMQ 관리 | http://127.0.0.1:15672 | 로컬 .env의 RABBITMQ_USERNAME과 PASSWORD로 확인 |

Compose 내부의 큐 주소는 rabbitmq:5672다. 호스트용 RABBITMQ_HOST=127.0.0.1을 컨테이너에서 그대로 쓰지 않는다.
LLM, 식약처와 KIPRIS 키는 아직 초기 기동에 필요하지 않다. 실제 adapter 담당자가 서버 설정에 추가한다.
Pika는 합성 AMQP smoke용 개발 의존성이며 실제 Worker 프레임워크를 확정한 의미가 아니다.

RabbitMQ 계정과 vhost는 새 볼륨의 첫 기동 때 생성된다. 이후 .env 비밀번호만 바꿔도 기존 계정이 자동 변경되지 않는다.
포트 충돌은 .env의 호스트 포트로 조정하고 DB 볼륨을 지우지 않는다.
기존 redis-queue 볼륨이 남아 있어도 이 변경에서 삭제하지 않는다. 이전 데이터는 자동 이관되지 않는다.
브로커가 켜졌다는 이유만으로 배치, DLQ, 자동 재시도 또는 과금이 구현됐다고 판단하지 않는다.
