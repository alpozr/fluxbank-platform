# Local development

This guide expands the [project README](../README.md). Commands and values are based on [compose.yaml](../compose.yaml), [Dockerfile](../Dockerfile), [pom.xml](../pom.xml), [application.yml](../src/main/resources/application.yml) and the [Maven Wrapper configuration](../.mvn/wrapper/maven-wrapper.properties). No application build or integration-test success is implied by these instructions.

## Prerequisites

- Docker with the Compose v2 plugin and an available Docker daemon.
- Ports 8080 and 9092 available on the host.
- Internet access for the first image pull and Maven dependency download.
- JDK 17 for host-side Maven tests or IDE development. The backend image includes its own Java build/runtime stages.
- curl for the optional API walkthrough. Windows users can use `mvnw.cmd test` in place of `./mvnw test`.

The Maven Wrapper pins Maven **3.8.1**. There is no need to install a separate system Maven. A Docker-only startup does not require a host Python installation; MockBank runs in its own image.

## Compose workflow

Run from the repository root:

```bash
docker compose up --build -d
docker compose ps
docker compose logs --tail=100 backend
```

The Dockerfile builds `src/main/` with tests skipped, packages the Spring Boot application and runs it as a non-root user. Testing is a separate developer step.

| Service | Purpose | Connectivity |
|---|---|---|
| backend | FluxBank Spring Boot application | Host `localhost:8080` |
| postgres | FluxBank PostgreSQL 16 database | Compose network `postgres:5432`; no published host port |
| kafka | Kafka 3.9.1, single-node plaintext KRaft | Backend `kafka:9092`; host `localhost:9092` |
| mockbank | Flask payment sandbox | Compose network `mockbank:5000`; no published host port |
| db | MockBank's separate PostgreSQL database | Private Compose network; no published host port |

The backend waits for PostgreSQL, Kafka and MockBank health checks. MockBank also depends on its database initialization. FluxBank's Flyway migrations run at application startup; Hibernate validates the schema in the Compose backend environment.

The sandbox initializes EUR settlement/recipient accounts through its own [fixtures](../docker/mockbank/fixtures.sql). Those fixtures do **not** fund FluxBank customer accounts. Newly created FluxBank accounts start with zero current and reserved balances. There is no deposit/funding REST endpoint in the current FluxBank controllers. Existing integration fixtures create funded accounts for financial scenarios; do not interpret sandbox funding as customer balance funding.

Stop services without deleting their named volumes:

```bash
docker compose down
```

`postgres_data`, `mockbank_data` and `kafka_data` preserve local state. Changing initialization credentials after a volume has been initialized does not recreate the database automatically. Investigate existing state before changing settings; this guide does not require deleting development data.

## Environment configuration

Compose supplies local defaults, so a `.env` file is optional. The root `.gitignore` excludes `.env` and `.env.*` except a potential `.env.example`; **no `.env.example` currently exists**. Do not make copying a nonexistent example file part of onboarding.

| Compose input | Default / role |
|---|---|
| POSTGRES_DB | `fluxbank_dev` |
| POSTGRES_USER | `fluxbank_dev` |
| POSTGRES_PASSWORD | Development-only value in compose.yaml |
| JWT_SECRET | Development-only Base64 signing key in compose.yaml |
| JWT_EXPIRATION_MS | 900000; mapped to `security.jwt.expiration-ms` by Compose |
| KAFKA_TRANSFER_EVENTS_TOPIC | `fluxbank.transfer-events.v1` |
| MOCKBANK_POSTGRES_DB | `mock_bank` |
| MOCKBANK_POSTGRES_USER | `mock_bank_user` |
| MOCKBANK_POSTGRES_PASSWORD | Development-only value in compose.yaml |

Compose assembles the backend's `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` from the POSTGRES variables. It sets `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` and `PAYMENT_PROVIDER_BASE_URL=http://mockbank:5000` for container networking.

The application itself also supports the settings below. **A host environment variable or Compose `.env` entry is not automatically passed to a container unless compose.yaml references it.** The current Compose backend forwards the transfer topic, but not every optional application variable.

| Application setting | Default / requirement |
|---|---|
| DB_URL, DB_USERNAME, DB_PASSWORD | Required for a JVM launched outside Compose |
| JWT_SECRET | Required outside Compose/test configuration; Base64-encoded HMAC key, at least 32 decoded bytes |
| KAFKA_BOOTSTRAP_SERVERS | `localhost:9092` outside Compose |
| KAFKA_TRANSFER_EVENTS_TOPIC | `fluxbank.transfer-events.v1` |
| KAFKA_TRANSFER_EVENTS_GROUP_ID | `fluxbank.transaction-history.v1` |
| KAFKA_TRANSFER_EVENTS_DLQ_TOPIC | Transfer-events topic plus `.DLT` |
| KAFKA_TRANSFER_EVENTS_CONSUMER_ENABLED | `true` |
| KAFKA_TRANSFER_EVENTS_MAX_RETRIES | `2` |
| KAFKA_TRANSFER_EVENTS_RETRY_INTERVAL_MS | `1000` |
| PAYMENT_PROVIDER_BASE_URL | `http://localhost:5001` outside Compose |
| PAYMENT_PROVIDER_SETTLEMENT_ACCOUNT | `FLOAT-MAIN-001` |
| PAYMENT_PROVIDER_RECIPIENT_ACCOUNT | `EXT-CUST-001` |

For an IDE/JVM workflow, configure reachable PostgreSQL, Kafka and provider endpoints before using `./mvnw spring-boot:run`. The provided Compose file does not expose PostgreSQL or MockBank to the host, and its MockBank service does not listen on the application's host default port 5001. Use infrastructure you provision separately or a private Compose override with explicit port mappings; merely starting the existing dependencies and launching Maven on the host is not sufficient.

Only development credentials and sandbox identities belong in a local setup. The Compose topology is not a production deployment: it uses one plaintext broker and local database defaults. Production connectivity and secret management are separate work.

## First API request

With the Compose backend running, check the protected identity endpoint:

```bash
curl -i http://localhost:8080/api/v1/auth/me
```

Expect 401 without authentication. There is no Actuator health endpoint configured in this project. Registration and login are public; `/me` and banking endpoints require a JWT.

Create a new local demo customer (the password below is an example you are creating, not a preconfigured credential):

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Local","lastName":"Developer","email":"developer@example.com","password":"LocalDemoPass123!"}'
```

On the first successful registration, the response is 201 and includes `publicId` and `customerNumber`. A repeated registration of an existing email is rejected. Log in using that same customer:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"developer@example.com","password":"LocalDemoPass123!"}'
```

The response contains `accessToken`, `tokenType` and `expiresIn`. Copy the returned token into the next request:

```bash
curl -i http://localhost:8080/api/v1/auth/me \
  -H 'Authorization: Bearer REPLACE_WITH_RETURNED_ACCESS_TOKEN'
```

Use the returned customer number when creating an account at `POST /api/v1/accounts`; the JSON fields are `customerNumber` and `currency` (BGN, EUR or USD). Saved recipients are managed under `/api/v1/beneficiaries`; transfers are submitted to `/api/v1/transfers`. The [architecture guide](architecture/README.md#http-authentication-and-routing) contains the transfer request and validation rules. Swagger/OpenAPI is planned in [FLUX-22](https://fluxbank.atlassian.net/browse/FLUX-22); no Swagger URL is available yet.

## Testing

Use JDK 17 and keep the Docker daemon running:

```bash
./mvnw test
```

Surefire's standard `*Test` selection includes the `*IntegrationTest` classes. PostgreSQL integration tests import a Spring-managed Testcontainers PostgreSQL 16 container. Broker integration tests start Kafka 3.9.1 containers and supply their own dynamic addresses/topics/groups. The integration profile disables ordinary Outbox schedulers and the consumer by default; relevant listener tests explicitly enable/control them.

Integration cleanup truncates **the Testcontainers database**; it is not an instruction to truncate the persistent Compose application database. Do not repoint these fixtures at real customer data.

For a quick, focused set of domain tests that does not require broker/database containers:

```bash
./mvnw -Dtest=AccountBalanceTest,TransferTest test
```

This is a targeted subset, not an alternative claim of full integration validation. No coverage percentage, benchmark or passing-test count is advertised by the README. Test reports from your own run are written under `target/surefire-reports/`.

### Troubleshooting

| Symptom | Check |
|---|---|
| Backend still starting | `docker compose ps`, then backend/Kafka/MockBank logs; dependency health checks may still be waiting. |
| Host port unavailable | Existing listeners on 8080/9092; the backend and Kafka both publish host ports. |
| Testcontainers cannot start | Docker daemon availability and permission to create containers/pull images. |
| Database login fails after changing `.env` | The existing initialized volume may retain earlier users/passwords. |
| IDE cannot reach database/provider | PostgreSQL and MockBank have no host port mappings in the supplied Compose file. |
| First transfer has insufficient funds | FluxBank accounts start at zero; provider fixtures fund only the sandbox float. |
| HTTP 201 with PENDING external transfer | Provider outcome may be UNKNOWN; no automatic reconciliation currently resolves it. |

## Repository conventions

The existing structure is one Maven/Spring Boot application, with domain-capability packages. Where present, layers are `api`, `application`, `domain`, `repository` and `infrastructure`. Use the surrounding module's structure as the starting point; not every capability has every layer.

There is no root CONTRIBUTING guide, Jenkinsfile, OpenAPI configuration or OpenShift manifest in the current tree. Suggested contribution practice is to link the relevant Jira issue, keep a change within its capability, update behavioral tests and keep architecture documentation consistent. This is guidance, not a claim that branch naming or PR rules are already enforced by tooling.

Use the [Jira milestone snapshot](../README.md#roadmap) for project direction and the live linked issues for current status. In particular, FLUX-20 is In Progress even though its implementation and test sources exist. Reading or documenting a planned issue does not start its feature development.

## Technical documentation preservation

At the start of this README task, the working tree and Git index contained **no root README.md**. There was therefore no root technical text to relocate. The detailed [transfer architecture reference](architecture/README.md), its BPMN files and the [upstream MockBank README](../docker/mockbank/README.md) were preserved in place. This guide adds onboarding/configuration context; the main README links into both it and the deeper architecture material.
