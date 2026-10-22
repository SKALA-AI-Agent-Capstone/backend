-- 목록 화면 썸네일(너비 160px JPEG). 첫 요청 때 원본으로 만들어 저장한다.
-- 만들 수 없는 원본(WebP 등)이나 원본이 없는 예전 파일은 비워 두며 기존 데이터는 바꾸지 않는다.
ALTER TABLE app.review_assets ADD COLUMN thumbnail bytea;
