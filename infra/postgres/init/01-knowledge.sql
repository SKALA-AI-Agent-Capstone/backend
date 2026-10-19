-- Public source corpus only. Private customer documents require a later,
-- reviewed migration with tenant authentication and row-level access controls.
SET ROLE rag_ingest;

CREATE TABLE knowledge.document_versions (
    document_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_key text NOT NULL,
    version_label text NOT NULL,
    title text NOT NULL,
    issuer text NOT NULL,
    collection_key text NOT NULL CHECK (collection_key IN ('rules', 'cases')),
    source_type text NOT NULL CHECK (
        source_type IN ('law', 'regulation', 'guideline', 'association', 'case')
    ),
    CHECK ((collection_key = 'cases') = (source_type = 'case')),
    jurisdiction char(2) NOT NULL DEFAULT 'KR',
    source_url text NOT NULL,
    source_verified boolean NOT NULL DEFAULT false,
    published_on date,
    effective_from date,
    effective_until date,
    retrieved_at timestamptz NOT NULL DEFAULT now(),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    storage_key text,
    corpus_version text NOT NULL,
    CHECK (effective_until IS NULL OR effective_from IS NULL OR effective_until >= effective_from),
    UNIQUE (document_key, version_label, content_sha256)
);

CREATE TABLE knowledge.chunks (
    chunk_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_version_id uuid NOT NULL REFERENCES knowledge.document_versions(document_version_id),
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    parent_chunk_id uuid REFERENCES knowledge.chunks(chunk_id),
    section text,
    page_from integer CHECK (page_from >= 1),
    page_until integer,
    body text NOT NULL CHECK (length(body) > 0),
    context text NOT NULL DEFAULT '',
    embedding_model text,
    embedding_revision text,
    embedding vector(1024),
    CHECK (page_until IS NULL OR (page_from IS NOT NULL AND page_until >= page_from)),
    CHECK (
        (embedding IS NULL AND embedding_model IS NULL AND embedding_revision IS NULL)
        OR
        (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_revision IS NOT NULL)
    ),
    UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX document_versions_corpus_idx ON knowledge.document_versions(collection_key, corpus_version, jurisdiction);
CREATE INDEX chunks_document_idx ON knowledge.chunks(document_version_id);
GRANT SELECT ON ALL TABLES IN SCHEMA knowledge TO rag_reader;
RESET ROLE;
