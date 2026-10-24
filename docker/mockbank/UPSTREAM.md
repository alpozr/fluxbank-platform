# Vendored MockBank for local development

Repository: https://github.com/BrokenHelmet/mock-bank-api

Pinned commit: `8ede25e100a8732673347f3db680927652790e13`

`src/`, `run.py`, `requirements.txt`, `Dockerfile`, `README.md`, and `LICENSE`
are copied unchanged from this commit. The upstream MIT license and attribution
are retained in `LICENSE`. No upstream Git metadata or environment file is included.

FluxBank additions are `.dockerignore`, `database.Dockerfile`, `init-db.sh`,
`fixtures.sql`, and this document. The database image packages initialization
files to avoid executable bind-mount permission differences across Docker hosts.
The provider is built directly from these checked-in sources; no
pre-existing image, external network, or external database is needed.
Upstream's Python requirements and base-image tags are unpinned; source is pinned,
but builds are not guaranteed to be byte-for-byte identical over time.

## Start and verify

From the FluxBank repository root, run `docker compose up -d --build`.
The backend is exposed on http://localhost:8080. MockBank is internal at
`http://mockbank:5000`; its database is `db:5432`. FluxBank uses `postgres:5432`.
All credential defaults in Compose are development-only.

The provider database initializes migrations 001–010 in filename order plus
fixtures in a single SQL transaction with `ON_ERROR_STOP`. A local migration
record and a schema-aware database healthcheck gate provider startup.
Upstream migration 001 creates an inline check named
`ledger_entries_entry_type_check`; 002 attempts to add that same name. The wrapper
drops the original check immediately before applying 002, within the same
transaction. Upstream migration files and provider business logic are unchanged.

Fixtures contain active EUR accounts `FLOAT-MAIN-001` (float), `EXT-CUST-001`
(external recipient), `BANK-CHARGES-001` (required by the transfer endpoint), and
`DEV-FUNDING-001` (internal funding source). A balanced deposit credits the float
with 1,000,000 development units. These fixtures follow the upstream funding
model; they are not real money. Use EUR for the demonstration flow.

Initialization runs only on an empty provider data volume. Container restarts
preserve schema, records, and balances; they do not replenish spent fixture funds.
If first initialization fails, inspect `docker compose logs db`, correct the cause,
then remove only the failed provider volume while services are down and retry.
Do not remove the FluxBank volume to repair the provider database.
Future upstream migration upgrades require an explicit upgrade procedure.

The upstream `/health` checks database connectivity only. The `db` healthcheck
separately verifies the migration count and all four required provider tables.
The upstream Flask development server is used only for local development.
