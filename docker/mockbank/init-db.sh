#!/bin/sh
set -eu

# postgres runs this only for a new data directory. One transaction ensures that
# schema, migration record, and development fixtures either all commit or fail.
sql_file=$(mktemp)
trap 'rm -f "$sql_file"' EXIT
printf '%s\n' 'BEGIN;' 'CREATE TABLE local_setup_migrations (version text PRIMARY KEY);' > "$sql_file"
for migration in /mockbank-migrations/0*.sql; do
    version=$(basename "$migration" | cut -d_ -f1)
    if [ "$version" = 002 ]; then
        # 001 implicitly names this inline check identically to 002's explicit
        # check. Replace it inside this transaction without altering upstream SQL.
        printf '%s\n' 'ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_entry_type_check;' >> "$sql_file"
    fi
    cat "$migration" >> "$sql_file"
    printf '\nINSERT INTO local_setup_migrations VALUES (\047%s\047);\n' "$version" >> "$sql_file"
done
cat /mockbank-fixtures.sql >> "$sql_file"
printf '\nCOMMIT;\n' >> "$sql_file"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f "$sql_file"
