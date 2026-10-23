-- 기존 검토를 제품에 연결한다. 같은 기업에서 제품 이름(앞뒤 공백 제외, 200자까지)이 같은 검토를 한 제품으로 묶는다.
-- 이름이 비어 있으면 '이름 없는 제품'으로 묶는다. 제품 유형은 예전 입력에 없으므로 general로 둔다.
-- 검토의 updated_at은 바꾸지 않는다(목록 순서 유지). 문서에는 프론트가 읽을 수 있게 productId를 넣는다.
-- 다시 실행해도 안전하다(이미 있는 제품은 그대로 두고, 연결되지 않은 검토만 연결한다).

INSERT INTO app.products (id, tenant_id, name, created_by, created_at, updated_at)
SELECT gen_random_uuid(), named.tenant_id, named.name,
       (array_agg(named.author_id ORDER BY named.updated_at))[1],
       min(named.updated_at), max(named.updated_at)
FROM (
  SELECT r.tenant_id, r.author_id, r.updated_at,
         coalesce(nullif(left(btrim(coalesce(r.document->>'productName', '')), 200), ''), '이름 없는 제품') AS name
  FROM app.reviews r
  WHERE r.product_id IS NULL
) AS named
GROUP BY named.tenant_id, named.name
ON CONFLICT (tenant_id, name) DO NOTHING;

UPDATE app.reviews r
SET product_id = p.id,
    document = jsonb_set(r.document, '{productId}', to_jsonb(p.id::text), true)
FROM app.products p
WHERE r.product_id IS NULL
  AND p.tenant_id = r.tenant_id
  AND p.name = coalesce(nullif(left(btrim(coalesce(r.document->>'productName', '')), 200), ''), '이름 없는 제품');

ALTER TABLE app.reviews ALTER COLUMN product_id SET NOT NULL;
