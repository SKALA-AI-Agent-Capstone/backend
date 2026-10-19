FROM pgvector/pgvector:0.8.7-pg17-bookworm
COPY init/00-bootstrap.sh init/01-knowledge.sql /docker-entrypoint-initdb.d/
RUN chmod 0644 /docker-entrypoint-initdb.d/00-bootstrap.sh \
    /docker-entrypoint-initdb.d/01-knowledge.sql
