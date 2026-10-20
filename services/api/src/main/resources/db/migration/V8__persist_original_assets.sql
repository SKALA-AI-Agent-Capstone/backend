-- 원본 폐기 원칙을 보존으로 바꾼다. Redis는 분석 중 빠른 조회용 캐시로만 남고,
-- 원본 바이트의 영구 저장소는 이제 Postgres다. 업로드 한도(건당 25MiB, 건별 합계 75MiB)가
-- 이미 있어 이 컬럼이 무제한으로 커지지 않는다.
ALTER TABLE app.review_assets ADD COLUMN bytes bytea;
