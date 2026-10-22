ALTER TABLE app.products DROP CONSTRAINT products_type_check;
ALTER TABLE app.products ADD CONSTRAINT products_type_check
  CHECK (type IN ('general', 'functional', 'children', 'customized'));
