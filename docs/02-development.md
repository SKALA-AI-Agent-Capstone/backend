# 개발환경

## 준비물

- Docker Engine이 실제 실행 중이어야 한다. Docker CLI 설치만으로는 충분하지 않다.
- Docker Compose 플러그인.
- 호스트 개발 시 Java 21과 Maven 3.9, Python 3.12와 uv 0.12.17, Node.js 24와 npm.
- frontend, backend를 같은 상위 디렉터리에 clone한다.

~~~sh
git clone https://github.com/SKALA-AI-Agent-Capstone/backend.git
git clone https://github.com/SKALA-AI-Agent-Capstone/frontend.git
cd backend
python3 scripts/bootstrap.py
docker compose config --quiet
docker compose up --build --wait
~~~

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
docker compose --profile queue up -d --wait redis-queue
~~~

web 프로필은 개발 서버의 컨테이너 실행이다. 코드 변경 후에는 이미지를 다시 빌드한다.
Vue를 빠르게 수정할 때는 DB와 백엔드를 컨테이너로 실행하고 frontend에서 npm run dev를 사용한다.
Redis 캐시는 비영구 설정이고 큐 서버는 별도 AOF 볼륨을 사용한다. Worker와 파일 업로드 기능은 없다.
기존 .env가 있으면 bootstrap.py를 다시 실행해 Redis 비밀번호만 추가할 수 있다. 기존 DB 비밀번호는 유지한다.

## 담당별 호스트 실행

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
