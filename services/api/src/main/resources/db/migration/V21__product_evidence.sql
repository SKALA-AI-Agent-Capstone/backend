-- 제품에 붙이는 증빙(시험 성적서 등). 파일 본체와 읽은 결과를 한 행에 두고, 제품과의 연결은 product_id로만 건다.
-- 같은 파일(sha256)은 제품 안에서 한 번만 읽는다. 같은 기관의 같은 코드인데 파일이 다르면 개정본으로 보고
-- 예전 행은 is_current=false로 남긴다(supersedes로 이어지며 지우지 않는다).
CREATE TABLE app.product_evidence (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES app.tenants(id),
  product_id uuid NOT NULL REFERENCES app.products(id) ON DELETE CASCADE,
  doc_type text NOT NULL DEFAULT 'other' CHECK (length(doc_type) <= 40),
  file_name text NOT NULL,
  file_mime text NOT NULL,
  file_size integer NOT NULL,
  file_sha256 text NOT NULL,
  file_bytes bytea NOT NULL,
  status text NOT NULL DEFAULT 'reading' CHECK (status IN ('reading', 'done', 'failed')),
  failure_code text,
  reader_version text,
  read_started_at timestamptz NOT NULL DEFAULT now(),
  read_finished_at timestamptz,
  issuer_key text,
  issuer_name text,
  codes jsonb NOT NULL DEFAULT '[]'::jsonb,
  version_label text,
  issued_on text,
  page_count integer,
  pages jsonb NOT NULL DEFAULT '[]'::jsonb,
  unread_pages jsonb NOT NULL DEFAULT '[]'::jsonb,
  report_products jsonb NOT NULL DEFAULT '[]'::jsonb,
  product_match jsonb,
  warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
  is_current boolean NOT NULL DEFAULT true,
  supersedes uuid REFERENCES app.product_evidence(id) ON DELETE SET NULL,
  change_note text,
  created_by uuid REFERENCES app.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, product_id, file_sha256)
);

CREATE INDEX product_evidence_product_idx ON app.product_evidence (tenant_id, product_id, created_at DESC);
-- 읽는 중인 증빙이 있는 제품을 검토 접수 때 바로 찾는다.
CREATE INDEX product_evidence_reading_idx ON app.product_evidence (product_id) WHERE status = 'reading';
