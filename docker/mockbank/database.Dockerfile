FROM postgres:16-alpine
COPY init-db.sh /docker-entrypoint-initdb.d/010-mockbank.sh
COPY src/db/migrations/ /mockbank-migrations/
COPY fixtures.sql /mockbank-fixtures.sql
# Source the initialization script through PostgreSQL's entrypoint.
RUN chmod 644 /docker-entrypoint-initdb.d/010-mockbank.sh
