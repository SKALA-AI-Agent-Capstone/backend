-- OCR과 비전 모델이 숫자를 다르게 읽은 쪽. 사람이 원본과 맞춰 봐야 하는 쪽이다.
ALTER TABLE app.product_evidence ADD COLUMN check_pages jsonb NOT NULL DEFAULT '[]'::jsonb;
