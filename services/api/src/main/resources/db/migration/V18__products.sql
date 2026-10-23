-- 제품 단위 검토(2026-10-22). 화장품 회사는 제품은 거의 그대로이고 광고만 바뀌므로 광고 검토를 제품 아래에 둔다.
-- 대표 이미지는 원본과 목록용 썸네일(너비 160px JPEG)을 함께 저장한다. 썸네일을 만들 수 없는 형식(WebP 등)이면 썸네일은 비어 있다.
-- image_sha256은 이미지가 바뀌었는지 프론트가 알 수 있게 하는 값이다(같은 URL의 브라우저 캐시를 피할 때 쓴다).
-- 증빙 서류와 증빙 요약은 다음 단계에서 붙인다.
CREATE TABLE app.products (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  name text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 200),
  type text NOT NULL DEFAULT 'general' CHECK (type IN ('general', 'functional')),
  image bytea,
  image_mime text,
  image_sha256 text,
  image_thumbnail bytea,
  created_by uuid REFERENCES app.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

-- 기존 검토는 V19가 제품에 연결한 뒤 NOT NULL로 바꾼다.
ALTER TABLE app.reviews ADD COLUMN product_id uuid REFERENCES app.products(id);
CREATE INDEX reviews_product_updated ON app.reviews(product_id, updated_at DESC);
