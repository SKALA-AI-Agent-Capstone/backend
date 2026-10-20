#!/usr/bin/env bash
set -euo pipefail

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --set db_name="$POSTGRES_DB" \
  --set app_password="$APP_DB_PASSWORD" \
  --set agent_password="$AGENT_DB_PASSWORD" \
  --set reader_password="$RAG_READER_DB_PASSWORD" \
  --set ingest_password="$RAG_INGEST_DB_PASSWORD" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
CREATE ROLE app_user LOGIN PASSWORD :'app_password';
CREATE ROLE agent_user LOGIN PASSWORD :'agent_password';
CREATE ROLE rag_reader LOGIN PASSWORD :'reader_password';
CREATE ROLE rag_ingest LOGIN PASSWORD :'ingest_password';
GRANT CONNECT ON DATABASE :"db_name" TO app_user, agent_user, rag_reader, rag_ingest;
GRANT USAGE ON SCHEMA public TO app_user, agent_user, rag_reader, rag_ingest;
CREATE SCHEMA app AUTHORIZATION app_user;
CREATE SCHEMA agent AUTHORIZATION agent_user;
CREATE SCHEMA knowledge AUTHORIZATION rag_ingest;
GRANT USAGE ON SCHEMA knowledge TO rag_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE rag_ingest IN SCHEMA knowledge
  GRANT SELECT ON TABLES TO rag_reader;
ALTER ROLE app_user SET search_path TO app, public;
ALTER ROLE agent_user SET search_path TO agent, public;
ALTER ROLE rag_ingest SET search_path TO knowledge, public;
ALTER ROLE rag_reader SET search_path TO knowledge, public;
ALTER ROLE rag_reader SET default_transaction_read_only TO on;
SQL
