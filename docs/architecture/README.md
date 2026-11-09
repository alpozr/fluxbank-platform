# FluxBank transfer architecture

Source-based BPMN 2.0 reference · current working tree · 8 November 2026

The financial transaction changes money. Outbox and Kafka transport the committed completion event. Transaction History records that movement later and **never debits or credits the financial balance again**. A producer ACK is not a History-consumer ACK. The HTTP response does not wait for History.

The models document the actual Spring Boot implementation, including uncommitted source files already present in the working tree. They do not implement FLUX-21, deploy executable processes or modify application code.

## Reading the diagrams

The four original filenames remain the entry points. Three focused companion pages keep security, History and recovery out of oversized boxes. All labels and Documentation fields are in English. Short task labels show the action, principal Java method and important state changes. Select a task and inspect **Documentation** for full identifiers, transaction semantics, database writes and exact source locations. The source index at the end of this guide gives the same references without requiring Java inspection.

| Page | Diagram | Read it for |
|---|---|---|
| 01 | [Transfer overview](fluxbank-transfer-overview.bpmn) | Independent API, worker, broker and consumer participants; INTERNAL/EXTERNAL routing; eventual History. |
| 02 | [HTTP entry and security](fluxbank-transfer-entry-detail.bpmn) | JWT, DTO validation, ownership, beneficiary lookup and local IBAN routing. |
| 03 | [Internal transfer](fluxbank-internal-transfer-detail.bpmn) | A → B, 100 EUR; separate PENDING transaction, locks, debit/credit, completion, Outbox and financial failure. |
| 04 | [External transfer](fluxbank-external-transfer-detail.bpmn) | Reservation, provider HTTP, SUCCESS/REJECTED/UNKNOWN, settlement/release and remaining uncertainty. |
| 05 | [Outbox and Kafka publication](fluxbank-kafka-outbox-history-detail.bpmn) | Short claim transaction, transaction-free broker wait, ACK, fenced result/failure transactions. Continue on page 06 or 07. |
| 06 | [History consumer](fluxbank-history-consumer-detail.bpmn) | Marker insertion, duplicate bypass, atomic History writes and separate Kafka offset commit. |
| 07 | [Retry, recovery and DLT](fluxbank-transfer-recovery-detail.bpmn) | Stale claims, failure budget, backoff, consumer classification and explicit DLT ACK/failure branches. |

**Camunda Desktop Modeler:** use File → Open File and choose a `.bpmn` file. Use the Diagram tab. Start at about 100% zoom and scroll vertically; the standard page width is about 1,120 diagram units. A normal task is roughly 250–340 units wide, so an individual task fits without extreme zooming out. Fit-to-viewport is useful for orientation, but fitting an entire long page into the height of a laptop screen necessarily reduces text size. No proprietary font or browser-only CSS is required. Properties → Documentation holds the longer details.

**IntelliJ on this machine:** IDEA 2021.3.3 has Camunda BPMN visualizer 0.5.4.3 installed. Its minimum IDE build is compatible, but its pool/lane/multiple-process support is insufficient for these collaboration diagrams: a one-box view is **not** a correct rendering of these models. Use Desktop Modeler for full editing, or a bpmn.io-compatible modeler. Do not infer document compatibility from IDE plugin load compatibility. [Plugin pool/lane issue](https://github.com/valb3r/flowable-bpmn-intellij-plugin/issues/224).

## Visual notation and arrows

| Symbol / appearance | Meaning |
|---|---|
| Pool | A participant or execution context. API, Outbox worker and consumer are separate contexts, not necessarily separate deployed microservices. |
| Lane | Responsibility or transaction phase within one participant. It is not a transaction by itself. |
| Solid arrow | Execution order within the same process/subprocess. A Java task may return before the next task starts; the arrow is not an extra invented repository call. |
| Dashed arrow with open arrowhead | HTTP or Kafka communication **between pools**. Labels identify request, response, publication or delivery. Broker ACK is explained on the publication task; it is not a promise of History completion. |
| Diamond | An actual choice. Each outgoing branch has an explicit outcome label. |
| Green expanded subprocess | A Spring/PostgreSQL transaction. A successful exit commits its enclosed writes; an exception/commit failure rolls them back. These are ordinary BPMN subprocesses, not distributed BPMN compensation transactions. |
| Red boundary event / red path | An exception and rollback path, not a second financial debit or automatic compensation. |
| Amber task/path | Retry, stale recovery, redelivery or unresolved outcome. |
| Blue task | HTTP/Kafka communication or Kafka offset phase. |
| Numbered task | The normal invocation order within that page. `06a` and `06b` are alternative branches, not sequential calls. |

There is deliberately **no message arrow from a financial transaction to the Outbox poller**. The financial transaction inserts an Outbox row; the worker discovers it later with SELECT after commit. Page references are documentation navigation, not runtime messages. Success and failure routes use separate corridors; long retry loops have been replaced by explicit continuation tasks on the focused recovery page.

The models are `isExecutable=false` architecture documents. They contain no Camunda engine execution bindings and should not be deployed as banking workflows. BEGIN/COMMIT labels represent Spring interceptor behavior, not invented Java methods. JPA dirty checking and flush can issue SQL later than the mutation method; displayed order is Java execution order, not a guaranteed SQL flush order.

## HTTP, authentication and routing

`POST /api/v1/transfers` invokes `TransferController.createTransfer()`. The request is:

```json
{
  "sourceAccountPublicId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "beneficiaryPublicId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "amount": 100.00
}
```

`CreateTransferRequest` requires both UUIDs and a positive, non-null BigDecimal amount with at most 17 integer digits and two fractional digits. The client does not supply currency or transfer type. `TransferResponse` returns publicId, referenceNumber, sourceAccountPublicId, beneficiaryName, destinationIban, destinationBankName, amount, currency, type, status, failureReason, createdAt and completedAt.

The exact entry chain is:

1. `SecurityConfig.securityFilterChain()`: stateless sessions, CSRF disabled, register/login public; other endpoints authenticated. No additional transfer-specific role requirement.
2. `JwtAuthenticationFilter.doFilterInternal()` → `JwtService.extractUsername()` → `CustomUserDetailsService.loadUserByUsername()`. Token parsing verifies signature/expiration. Disabled users are rejected; authorities are derived from the user's role. Invalid JWT or disabled user returns 401. Missing token reaches the protected endpoint entry point and is rejected. The filter does not catch every possible technical exception.
3. `TransferController.createTransfer()` applies `@Valid` and passes `authentication.getName()` to `TransferService.createTransfer()`.
4. `UserRepository.findByEmail()` → `AccountRepository.findByPublicIdAndUser()` → `BeneficiaryRepository.findByPublicIdAndOwnerAndActiveTrue()`. Missing/wrong-owner source or missing/inactive/wrong-owner beneficiary is rejected. Destination ownership need not match the sender.
5. `AccountRepository.findByIban(beneficiary.getIban())`: local match selects `TransferService.processInternalTransfer()`; no match selects `TransferService.processExternalTransfer()`. No bank-prefix heuristic or client-supplied type is used. A same-account INTERNAL transfer is rejected before PENDING creation.

TransferService has no encompassing `@Transactional`. Currency comes from the source account. Transfer-time lookup uses the saved beneficiary; it does not repeat IBAN normalization/checksum validation. Beneficiary creation has its own `BeneficiaryService.create()` and `IbanValidator.normalize()/isValid()` checks.

`GlobalExceptionHandler` maps DTO validation to 400, source/beneficiary missing to 404, same account/insufficient funds/currency mismatch to 422 and source/destination unavailability to 409. There is no special failure mapping for every infrastructure exception.

The controller normally returns **201**, even if an EXTERNAL result is FAILED or remains PENDING after UNKNOWN. 201 therefore does not mean confirmed external settlement or completed History. `getResponse(UUID)` is an internal helper, not a new authenticated GET endpoint.

## Account and money model

`Account` stores owner, account number, IBAN, currency and the independent boolean flags closed/dormant/blocked/expired. `AccountBalance` owns current_balance, reserved_balance and monetary mutations. `available = current − reserved` is calculated, not stored as another column. All mutation amounts must be positive. Amounts are BigDecimal; database monetary columns are NUMERIC(19,4), while the transfer request limits input to two fractional digits.

| Operation | Current | Reserved | Available |
|---|---|---|---|
| `AccountBalance.debit(100)` | −100 | unchanged | −100 |
| `AccountBalance.credit(100)` | +100 | unchanged | +100 |
| `AccountBalance.reserve(100)` | unchanged | +100 | −100 |
| `AccountBalance.release(100)` | unchanged | −100 | +100 |
| `AccountBalance.settle(100)` | −100 | −100 | unchanged |

Debit/reserve require sufficient available balance; release/settle require sufficient reserved balance. Account validation is invoked by the financial service, not automatically inside every balance mutation. Outgoing transfers reject closed, dormant, blocked or expired accounts. Incoming credit rejects closed or expired accounts; blocked/dormant may still receive money. Finalization validates EXTERNAL/PENDING and reservation sufficiency, without rerunning the original outgoing account-flag check.

## INTERNAL: A → B, 100 EUR

Example starting state: A=(current 1000, reserved 0, available 1000), B=(500,0,500), both EUR.

1. `TransferService.processInternalTransfer()` rejects A=B, then invokes `TransferLifecycleService.createPending()` in **TX-PENDING**.
2. `TransferReferenceGenerator.nextReference()` executes `nextval('fluxbank.transfer_reference_seq')`, producing `TRX-yyyyMMdd-sequence`. `Transfer.create()` assigns a public UUID and PENDING status. `TransferRepository.save()` inserts the row and the Spring proxy commits. The exact sequence number is not hard-coded.
3. `InternalTransferProcessor.process()` starts **TX-FINANCIAL**. `TransferRepository.findByPublicIdForUpdate()` locks the Transfer and requires PENDING/INTERNAL.
4. `AccountBalanceRepository.findAllByAccountPublicIdInForUpdate()` locks both balances with PESSIMISTIC_WRITE and `ORDER BY ab.account.id ASC`. Exactly two rows must be found; source/destination are resolved by UUID, not list position. Deterministic order reduces deadlock risk; it does not prove deadlocks are impossible.
5. Require matching currency; call `Account.validateAvailableForDebit()` and `Account.validateAvailableForCredit()`.
6. `AccountBalance.debit(100)` changes A current 1000→900. `AccountBalance.credit(100)` changes B current 500→600. Reserved remains zero for both.
7. `Transfer.complete()` sets COMPLETED, completed_at=now and failure_reason=null. `OutboxTransferEventPublisher.publishCompleted()` is **MANDATORY** and joins the same transaction. It reloads/verifies completion, builds `TransferCompletedEvent`, and saves a new `OutboxEvent` through `OutboxEventRepository.save()`.
8. Commit both balance UPDATEs, Transfer completion UPDATE and Outbox INSERT **together**. Runtime/DB/Outbox failure rolls all these changes back. The earlier PENDING row remains committed.
9. `TransferLifecycleService.getResponse()` performs a separate readOnly transaction; the controller returns 201 without awaiting consumer processing. Continue on page 05, then 06.

Only four mapped business exceptions cause a separate `TransferLifecycleService.markFailed()` call after financial rollback:

| Exception | Stored TransferFailureReason |
|---|---|
| InsufficientFundsException | INSUFFICIENT_FUNDS |
| AccountNotAvailableForDebitException | SOURCE_ACCOUNT_UNAVAILABLE |
| AccountNotAvailableForCreditException | DESTINATION_ACCOUNT_UNAVAILABLE |
| CurrencyMismatchException | CURRENCY_MISMATCH |

The original exception is rethrown. `markFailed()` uses `findByPublicId()`, not the explicit locking query. An unexpected technical exception can leave the previously inserted transfer PENDING; no pending-transfer reconciliation job is implemented. Failure of the separate markFailed transaction has no automatic compensation. Transfer.complete()/fail() reject terminal states; fail(null) is rejected; there is no failedAt field.

## EXTERNAL: A → external bank, 100 EUR

`TransferService.processExternalTransfer()` → `ExternalTransferReservationService.createPendingAndReserve()` starts **TX-RESERVATION**. Lock the source balance with `findByAccountPublicIdForUpdate()`, validate debit availability, generate a reference and create EXTERNAL/PENDING with destination_account_id=null. `AccountBalance.reserve(100)` and `TransferRepository.save()` commit together. A becomes (1000,100,900). An insertion/constraint/reference failure rolls back reservation and transfer creation and prevents the provider call. The returned `ReservedExternalTransfer` contains UUID, reference, beneficiary snapshot, amount and currency String.

After that commit, `ExternalTransferProcessor.process()` builds `PaymentProviderRequest` and invokes `MockBankPaymentProviderClient.sendPayment()` **outside the financial DB transaction**. The adapter constructs `BankTransferRequest` and performs `RestClient.post().uri("/idempotent-transfers").body(...).retrieve().body(...)`.

| HTTP payload field | Source |
|---|---|
| idempotency_key, description | Transfer reference |
| from_account | payment-provider.settlement-account; default FLOAT-MAIN-001 |
| to_account | payment-provider.recipient-account; default EXT-CUST-001 |
| amount, currency | 100 / EUR from reservation DTO |

The beneficiary name/IBAN/bank name in PaymentProviderRequest are **not mapped into the MockBank HTTP DTO**. This is a development adapter moving between configured provider accounts, not verified real beneficiary-IBAN routing. PaymentProviderConfig configures base URL only; no explicit timeout or HTTP retry is configured. The exact transport timeout cannot be inferred from this config.

| Adapter outcome | Exact local path | A current / reserved / available | Event / History |
|---|---|---|---|
| Non-null response, top-level status=completed, case-insensitive | SUCCESS → `ExternalTransferFinalizationService.complete()` | 1000/100/900 → 900/0/900 | Completion Outbox; later only A DEBIT |
| HTTP 4xx | REJECTED → `ExternalTransferFinalizationService.fail()` | 1000/100/900 → 1000/0/1000 | FAILED / EXTERNAL_PROVIDER_REJECTED; no completion event or History |
| 5xx, resource/network/timeout error, decode/RestClientException, null/non-completed response | UNKNOWN → process returns without finalization | Remains 1000/100/900 | PENDING; no Outbox/History |

**TX-FINANCIAL / settlement:** lock Transfer → validateExternalPending() → lock source balance → settle(100) → Transfer.complete() → mandatory Outbox publisher → commit. **TX-REJECTION:** the same lock/state order → release(100) → Transfer.fail(EXTERNAL_PROVIDER_REJECTED) → commit. Each scope is atomic locally. Other reservations are preserved: reserved 300 becomes 200 when this transfer's 100 is settled/released.

Financial exceptions roll back the affected finalization transaction; the earlier reservation stays committed. Provider SUCCESS followed by local commit failure can leave PENDING/reserved in FluxBank even though the provider paid. There is no distributed transaction, automatic reconciliation, automatic resend or provider-status lookup in this flow. The providerReference returned by the adapter is not persisted by ExternalTransferProcessor.

MockBank's `docker/mockbank/src/routes/transfer_routes.py:create_idempotent_transfer_endpoint()` checks a stable idempotency key. Initial success returns flat status; a duplicate can return a nested transaction object. Because the FluxBank adapter checks top-level status, a duplicate provider response is not guaranteed to map to SUCCESS. Provider fees/ledger changes do not cause additional FluxBank financial balance writes in this code path.

## Transaction scopes

These names are documentation labels, not extra Java classes. REQUIRED joins an existing transaction; because TransferService has no outer transaction, the normal HTTP calls shown below commit separately. Default Spring RuntimeException rollback rules apply.

| Scope | Entry / propagation | Atomic effect |
|---|---|---|
| TX-PENDING | TransferLifecycleService.createPending / REQUIRED | INTERNAL Transfer INSERT. Survives later financial rollback. |
| TX-FINANCIAL internal | InternalTransferProcessor.process / REQUIRED | Balance locks and debit/credit UPDATEs + COMPLETED UPDATE + Outbox INSERT. |
| TX-FAILED | TransferLifecycleService.markFailed / REQUIRED | Separate FAILED UPDATE after one of four mapped business exceptions. |
| TX-RESERVATION | ExternalTransferReservationService.createPendingAndReserve / REQUIRED | Reserve UPDATE + EXTERNAL PENDING INSERT. |
| PROVIDER-HTTP | ExternalTransferProcessor.process / no enclosing TX | Remote HTTP; reservation already committed. |
| TX-FINANCIAL external | ExternalTransferFinalizationService.complete / REQUIRED | Settle UPDATE + COMPLETED UPDATE + Outbox INSERT. |
| TX-REJECTION | ExternalTransferFinalizationService.fail / REQUIRED | Release UPDATE + FAILED UPDATE; no completion Outbox. |
| TX-OUTBOX-CLAIM | OutboxClaimService.claimBatch / REQUIRES_NEW | Eligible SELECT SKIP LOCKED + PROCESSING UPDATE; commit before publication. |
| KAFKA-PUBLISH | OutboxProcessor.process / NOT_SUPPORTED | Read event, send and await ACK outside claim DB transaction. |
| TX-OUTBOX-RESULT | OutboxProcessingTransaction.markProcessed / REQUIRES_NEW | Lock + matching attempt → PROCESSED UPDATE. |
| TX-OUTBOX-FAILURE | OutboxFailureService.recordFailure / REQUIRES_NEW | Lock + attempt fence + retry/FAILED UPDATE. |
| TX-OUTBOX-RECOVERY | OutboxRecoveryService.recoverStaleBatch / REQUIRES_NEW | Stale SKIP LOCKED batch + failure-budget/backoff UPDATEs. |
| TX-HISTORY | TransactionHistoryEventProcessor.process / REQUIRED | Marker INSERT + all required History INSERTs, including joined History service. |
| KAFKA-OFFSET | Spring Kafka container / RECORD | Separate Kafka group offset commit after successful listener return. No XA. |
| Response read | TransferLifecycleService.getResponse / readOnly | SELECT and DTO mapping, no History availability guarantee. |

## Database write inventory

All table names below use PostgreSQL schema **fluxbank**. JPA entities mostly use unqualified @Table names; Hibernate default_schema and Flyway default-schema are fluxbank. Native SQL explicitly qualifies the schema. Transfer processing reads users/accounts/beneficiaries; it does not create or update them. No DELETE occurs in this lifecycle.

| Write / exact calling methods | Table and important values | Scope |
|---|---|---|
| createPending → Transfer.create → TransferRepository.save | transfers INSERT: UUID/reference, owner/source/beneficiary/destination FK, snapshot, 100 EUR, INTERNAL/PENDING; failure_reason/completed_at null | TX-PENDING |
| createPendingAndReserve → TransferRepository.save | transfers INSERT: EXTERNAL/PENDING; destination_account_id null; same snapshot/reference fields | TX-RESERVATION |
| InternalTransferProcessor.process → AccountBalance.debit/credit | account_balances UPDATE: A current−100, B current+100; reserved unchanged; updated_at audit | TX-FINANCIAL |
| createPendingAndReserve → AccountBalance.reserve | account_balances UPDATE: reserved+100, current unchanged, updated_at | TX-RESERVATION |
| ExternalTransferFinalizationService.complete → AccountBalance.settle | account_balances UPDATE: current−100, reserved−100, updated_at | TX-FINANCIAL |
| ExternalTransferFinalizationService.fail → AccountBalance.release | account_balances UPDATE: reserved−100, current unchanged, updated_at | TX-REJECTION |
| Financial processor → Transfer.complete | transfers UPDATE: COMPLETED, completed_at=Instant.now(), failure_reason=null, updated_at | Same financial TX |
| markFailed or external fail → Transfer.fail | transfers UPDATE: FAILED, failure_reason=<reason>, completed_at=null, updated_at | TX-FAILED / TX-REJECTION |
| OutboxTransferEventPublisher.publishCompleted → OutboxEvent.create → OutboxEventRepository.save | outbox_event INSERT: public_id UUID, TRANSFER_COMPLETED, aggregate_id=Transfer UUID, payload JSONB, PENDING, attempts=0, created_at; claim/retry/result/error null | Same financial TX; MANDATORY |
| OutboxClaimService.claimBatch → OutboxEvent.claim | outbox_event UPDATE: PROCESSING, claimed_at=now; attempts unchanged | TX-OUTBOX-CLAIM |
| OutboxProcessingTransaction.markProcessed → OutboxEvent.markProcessed | outbox_event UPDATE: PROCESSED, processed_at=now, next_attempt_at/last_error null; attempts/claimed_at retained | TX-OUTBOX-RESULT |
| OutboxFailureService.recordFailure or recoverStaleBatch → OutboxEvent.recordFailure | outbox_event UPDATE: attempts+1, sanitized last_error; retry→PENDING/next_attempt_at/backoff/claimed_at null; terminal→FAILED/next_attempt_at null | FAILURE / RECOVERY |
| TransactionHistoryEventProcessor.process → ProcessedEventRepository.insertIfAbsent | processed_event INSERT event_id PK, event_type; processed_at default CURRENT_TIMESTAMP; ON CONFLICT DO NOTHING | TX-HISTORY |
| TransactionHistoryService.createForSnapshot → createMovements → createMovement → Transaction.createTransferMovement → TransactionRepository.saveAll/save | transactions INSERT: per-row UUID/TXN reference; account_id A/B, transfer_id, TRANSFER, DEBIT/CREDIT, positive amount=100, EUR, created_at | Same TX-HISTORY; INTERNAL 2 rows, EXTERNAL 1 |

Dirty-check UPDATEs do not require an invented balance repository save() call. Sequence nextval modifies sequence state, which does not roll back; gaps are normal. Identity PK, public UUID and public reference are different identifiers. Transfer references use TRX; History references use TXN, generated by TransactionReferenceGenerator.nextReference(). Dates use LocalDate.now(), not an explicit bank timezone.

## Schema and constraints

| Migration / entity | Relevant rules |
|---|---|
| V2–V4 / User | public UUID/customer number uniqueness; LOWER(email) unique index. findByEmail is not thereby automatically case-insensitive. |
| V5–V6 / Account | owner FK; account number, IBAN, public UUID and IBAN identifier unique; owner+suffix unique, suffix 1..999. |
| V7 / Beneficiary | owner FK; owner+IBAN unique, not globally unique; active flag. |
| V8 / Transfer | UUID/reference unique; FKs; positive amount; FAILED requires failure_reason, COMPLETED requires completed_at; INTERNAL requires destination, EXTERNAL forbids destination. No exhaustive enum CHECK beyond actual expressions. |
| V9 / AccountBalance | unique account FK; current>=0, reserved>=0, reserved<=current. Old accounts.balance migrated and dropped. |
| V10 / Transaction | account FK required; transfer FK nullable in schema, required by transfer-movement factory; positive amount; unique(transfer_id,account_id,type,direction); UUID/reference unique. |
| V11–V13 / OutboxEvent | JSONB payload; UUID unique; PENDING/PROCESSING/PROCESSED/FAILED CHECK; attempts>=0; claimed_at/next_attempt_at; partial pending(created_at,id) and processing(claimed_at,id) indexes. aggregate_id has no Transfer FK. |
| V14 / ProcessedEvent | event_id PK; event_type NOT NULL; processed_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP. No consumer-group namespace or Transfer FK. |

Main application.yml uses Hibernate ddl-auto=none. Flyway V1–V14 describes schema; integration/Compose validation does not mean Hibernate creates it. No ON DELETE CASCADE should be assumed. Runtime database schema was not queried for this documentation revision.

## Outbox, publication and retry

OutboxTransferEventPublisher.publishCompleted() enforces MANDATORY, requires COMPLETED, captures the completion snapshot and saves it in the same financial transaction. It does not directly invoke History or Kafka.

OutboxPoller.poll() runs with default fixedDelay=2000 ms and batchSize=20. OutboxClaimService.claimBatch() opens REQUIRES_NEW and uses:

```sql
SELECT * FROM fluxbank.outbox_event
WHERE status = 'PENDING'
  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
ORDER BY created_at ASC, id ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

Each row is changed to PROCESSING/claimed_at before returning UUIDs. Competing instances skip locked rows. Locks are released at commit before broker waiting. The worker processes the batch sequentially; a per-event RuntimeException is logged and processing continues with the next event.

OutboxProcessor.process() is NOT_SUPPORTED. It loads the event, accepts only PROCESSING, captures OutboxAttempt(publicId,status,attempts,claimedAt), calls TransferCompletedKafkaPublisher.publish(), then calls markProcessed(). The publisher parses the stored snapshot and invokes `KafkaTemplate.send(topic, transferPublicId.toString(), event).join()`. A new eventId is **not** generated.

OutboxProcessingTransaction.markProcessed() starts a short REQUIRES_NEW transaction, locks the row and checks PROCESSING plus an identical attempt snapshot. A stale worker cannot complete a newer claim. It sets PROCESSED and processed_at and clears next_attempt_at/last_error. Publish or result failure invokes OutboxFailureService.recordFailure() separately, with the same attempt fence; failure bookkeeping errors are added as suppressed exceptions to the original failure. Fencing protects DB bookkeeping; it does not prevent duplicate Kafka publication.

| Outbox property | Default |
|---|---|
| outbox.polling.enabled / interval-ms / batch-size | true / 2000 / 20 |
| outbox.retry.max-attempts | 5 |
| outbox.retry.initial-delay / max-delay | PT5S / PT5M |
| outbox.processing-timeout | PT5M |
| outbox.recovery.enabled / interval-ms / batch-size | true / 60000 / 20 |

OutboxRetryPolicy.isRetryable() accepts TransientDataAccessException, RecoverableDataAccessException, CannotCreateTransactionException and Kafka RetriableException. It unwraps CompletionException, ExecutionException and Spring KafkaException wrappers only. Other exceptions are permanent. Consumer nested-cause classification is a different policy.

`attempts` counts failures/stale recoveries, not claims. recordFailure() increments attempts and stores a sanitized category in last_error. If retryable and attempts<5, set PENDING, next_attempt_at=now+min(5s×2^(attempt−1),5m), clear claimed_at. Defaults schedule 5s/10s/20s/40s; the fifth failure becomes FAILED. A permanent error may fail immediately. FAILED retains its row, clears next_attempt_at and does not clear claimed_at. No automatic delete or re-drive is implemented.

OutboxRecoveryScheduler.recover() invokes OutboxRecoveryService.recoverStaleBatch() in REQUIRES_NEW:

```sql
SELECT * FROM fluxbank.outbox_event
WHERE status = 'PROCESSING' AND claimed_at < :cutoff
ORDER BY claimed_at ASC, id ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

cutoff=now−processingTimeout. Recovery consumes the same failure budget via recordFailure(true,...), resulting in PENDING+backoff or FAILED. It does not publish; the ordinary poller later claims due PENDING rows. A long sequential batch or broker wait may overlap stale recovery. An ACK followed by crash before PROCESSED leaves a stale PROCESSING claim and can cause republishing with the original eventId. This is at-least-once delivery, not distributed exactly-once processing.

## Kafka event contract and configuration

| Event field | Java / wire type | Meaning |
|---|---|---|
| eventId | UUID / string | Outbox public_id; stable across retry/recovery |
| eventType | String | TRANSFER_COMPLETED |
| aggregateId | UUID / string | Must equal transferPublicId |
| occurredAt | Instant / ISO-8601 string | Outbox createdAt, not Transfer completedAt |
| transferPublicId | UUID / string | Transfer public UUID; also Kafka String key |
| transferType | TransferType / string | INTERNAL or EXTERNAL |
| sourceAccountPublicId | UUID / string | Source account identity |
| destinationAccountPublicId | UUID / string or absent/null | Required INTERNAL; null EXTERNAL |
| amount | BigDecimal / JSON number in Kafka | Positive; Outbox JSONB snapshot serializes amount as a string to preserve precision |
| currency | CurrencyCode / string | BGN, EUR or USD; example EUR |

TransferCompletedEvent/TransferCompletedKafkaEvent validate required identity/envelope/snapshot fields, positive amount and destination/type combinations. Unknown JSON fields are tolerated. A legacy ID-only envelope may deserialize but is rejected by TransactionHistoryEventProcessor before marker insertion. The publisher does not reconstruct an incomplete legacy business snapshot from later database state. Snapshot-to-Transfer account/amount consistency is not comprehensively revalidated; the design trusts its producer.

| application.yml setting | Effective default |
|---|---|
| spring.kafka.bootstrap-servers | KAFKA_BOOTSTRAP_SERVERS or localhost:9092; Compose backend uses kafka:9092 |
| kafka.topics.transfer-events | KAFKA_TRANSFER_EVENTS_TOPIC or fluxbank.transfer-events.v1 |
| spring.kafka.consumer.group-id | KAFKA_TRANSFER_EVENTS_GROUP_ID or fluxbank.transaction-history.v1 |
| kafka.topics.transfer-events-dlq | KAFKA_TRANSFER_EVENTS_DLQ_TOPIC or `<transfer-events>.DLT` |
| consumer.enable-auto-commit / listener.ack-mode | false / record |
| consumer.auto-offset-reset | earliest; applies when group offset is absent/reset, not on every start |
| consumer value deserializer | ErrorHandlingDeserializer → JsonDeserializer; fixed TransferCompletedKafkaEvent class; type headers ignored |
| producer key/value serializer | StringSerializer / JSON; no JSON type headers |
| producer.acks / enable.idempotence | all / true |
| kafka.consumer.enabled | true |
| kafka.consumer.max-retries / retry-interval-ms | 2 / 1000 |

These are configurable defaults, not claims about environment overrides. A transferPublicId key normally routes the same transfer to the same partition for a stable partition count; ordering is within a partition. Different transfers from one account have different keys and no account-wide ordering guarantee. Multiple workers, SKIP LOCKED and retry may reorder publication relative to financial commit. Financial correctness comes from PostgreSQL locks and transactions, not Kafka ordering. Producer idempotence does not deduplicate independent application resends after a crash. Topic partition count, replication and retention are not provisioned by a production NewTopic bean here; local Compose is a single-broker development setup.

## Atomic History and idempotency

TransferEventsKafkaListener.handle() delegates to TransactionHistoryEventProcessor.process(). It does not duplicate business rules. process() opens REQUIRED **TX-HISTORY**, validates the snapshot and executes:

```sql
INSERT INTO fluxbank.processed_event (event_id, event_type)
VALUES (:eventId, :eventType)
ON CONFLICT (event_id) DO NOTHING;
```

ProcessedEventRepository.insertIfAbsent() returning 0 means **return immediately**, with no History service call. Returning 1 invokes TransactionHistoryService.createForSnapshot() with REQUIRED, joining the same transaction. The marker is not durable until that transaction commits. It rolls back with History on any failure.

createForSnapshot() → completedTransfer() requires a local COMPLETED Transfer. AccountRepository.findByPublicId() loads source/destination identities from snapshot UUIDs. createMovements() checks existing business movements through TransactionRepository.existsByTransferAndAccountAndTypeAndDirection(). INTERNAL with neither row inserts both; with both rows does nothing; a partial pair throws TransactionHistoryInconsistentException. EXTERNAL inserts only source DEBIT if absent. There is no automatic partial-history healing.

createMovement() → TransactionReferenceGenerator.nextReference() → Transaction.createTransferMovement() → TransactionRepository.saveAll()/save() produces positive 100 EUR amounts with DEBIT/CREDIT direction. Each row has a separate UUID and TXN reference. Marker plus both INTERNAL rows, or the single EXTERNAL row, commit together. A failed credit insert rolls back the debit and marker. Prior financial completion is not rolled back.

Concurrent calls with the same eventId wait on the PostgreSQL PK conflict: first TX commit→second insert returns 0; first TX rollback→waiter may insert and process. A different eventId for the same movement is also constrained by the business unique key. Exists checks alone do not guarantee conflict-free concurrent processing of different eventIds; an unrelated integrity violation is not swallowed as successful deduplication. The marker PK is global event_id, not scoped to consumer group/handler.

After process() commits, the listener returns and Spring Kafka's RECORD mode commits its source offset. PostgreSQL commit and Kafka offset commit are separate. A crash/offset failure in between may redeliver the record; the marker then causes a no-op, preventing duplicate History effects. The claim is **at-least-once delivery plus idempotent atomic History**, not exactly-once Kafka delivery.

The older TransactionHistoryService.createForTransfer(UUID) remains available but is not the listener path. History is still coupled to local Transfer/Account FKs and is not yet an autonomous microservice consumer.

## Consumer retry and DLT

TransferEventsConsumerConfiguration.transferEventsErrorHandler() configures DefaultErrorHandler with FixedBackOff(1000ms,2): initial delivery plus two retries, up to three business deliveries. Only TransientDataAccessException, RecoverableDataAccessException and CannotCreateTransactionException are retryable; nested causes are considered. Other failures default to non-retryable.

- Malformed JSON: ErrorHandlingDeserializer records the failure; business listener is not invoked; recover to DLT with the original byte[].
- Invalid/incomplete/legacy snapshot: structural validation fails; no marker/History commit; direct DLT.
- Temporary DB failure: roll back TX-HISTORY, seek/redeliver the same source offset, then DLT after exhaustion.
- Missing local Transfer/account, partial INTERNAL History or integrity error: non-retryable by default; no partial repair.

DeadLetterPublishingRecoverer uses destination partition −1 and waits for the DLT send result because setFailIfSendResultIsError(true). DLT ACK allows recovery to complete and the source offset to advance. Async/sync publish failure or ACK timeout fails recovery: source offset does not pass the record and seek/redelivery preserves it for another recovery attempt. setResetStateOnRecoveryFailure(false) prevents restarting the business retry budget after a recovery failure. Finite business retry does not mean records are dropped during a DLT outage.

DelegatingByTypeSerializer supports normal event JSON and raw malformed byte[]. Spring Kafka adds original topic/partition/offset and exception headers; DLT headers may contain exception details even though Outbox.lastError uses sanitized categories. No production DLT replay consumer/endpoint/scheduler is implemented.

## Failure and recovery matrix

| Failure window | Persistent effect / recovery |
|---|---|
| Financial UPDATE or Outbox INSERT fails | All TX-FINANCIAL writes roll back; earlier INTERNAL PENDING survives; mapped business exceptions cause separate FAILED update. |
| Reservation INSERT/reference failure | Reserve + PENDING insertion roll back; no provider call. |
| Kafka publication fails | Finance unaffected; fenced failure TX schedules retry or FAILED. |
| Producer ACK, then crash before PROCESSED | Stale PROCESSING recovery; same eventId may publish again. |
| Stale claim | REQUIRES_NEW + SKIP LOCKED; consumes failure budget; normal poller later claims due PENDING. |
| History processing/commit fails | Marker and all required History roll back together; retry classification or DLT. |
| History commit succeeds, offset commit fails | Possible redelivery; marker conflict→no-op; no duplicate balance or History effect. |
| Duplicate or concurrent same eventId | PK conflict/no-op; concurrent rollback allows a waiter to process. |
| Legacy/incomplete event | Reject before marker; direct DLT. |
| Malformed JSON | No listener invocation; raw bytes DLT. |
| Consumer retry exhausted | DLT publish; source offset advances only after successful recovery. |
| DLT publication fails | Preserve source offset, seek/redeliver; recovery retried. |
| External SUCCESS | Local settle + complete + Outbox transaction; source-only DEBIT History later. |
| External REJECTED | Local release + FAILED transaction; no completion event/History. |
| External UNKNOWN or provider-paid/local-commit-failed | PENDING/reservation uncertainty; no implemented reconciliation. |

## Known limitations and future work

- No REST request idempotency key or repeated-POST-to-original-transfer lookup. A repeated POST may create another transfer/reference; consumer and provider idempotency do not prevent that new request.
- No recovery job for ordinary PENDING transfers or external UNKNOWN; no automatic provider compensation/status lookup; providerReference is not persisted.
- No automated re-drive for FAILED Outbox or DLT; no automatic marker/History retention cleanup. Operations, alerting and retention policies need separate design.
- No distributed transaction across PostgreSQL/Kafka or FluxBank/provider. Producer ACK/result UPDATE and History commit/offset commit have duplicate-delivery windows.
- No autonomous History store: local Transfer/Account FKs remain. Trusted snapshots are structurally validated without a comprehensive semantic comparison against all local Transfer fields.
- Global marker event_id could interfere with future independent handlers sharing the same table. Multiple consumer-handler namespaces would require a new design, not assumed here.
- Sequential Outbox batches can overlap stale timeout under long broker waits. Fencing prevents stale DB results, not duplicate publication. Lock ordering reduces, but does not eliminate, deadlock risk; no invented financial retry is documented.
- Explicit HTTP timeout/retry, production Kafka topology/security/provisioning and full crash/rebalance testing are outside this implementation.

## Verification evidence

The application test suite was **not run** for this documentation-only revision. Existing sources were reviewed as evidence of intended/tested behavior; no old test count is presented as a new successful run.

| Existing tests | Relevant assertions/scenarios |
|---|---|
| TransferTransactionsIntegrationTest | Financial/Outbox atomicity, separately committed PENDING, rollback, reservation/settlement/release and History isolation. |
| PostgresConcurrencyIntegrationTest | Balance lock blocking/order, concurrent overspending and double settlement prevention. |
| OutboxClaimIntegrationTest | Due/order/limit selection, independent claim commit, competing workers and SKIP LOCKED. |
| OutboxProcessorIntegrationTest | No DB lock during broker ACK wait, immutable snapshot, attempt fencing, ACK/result gap; producer mocked in this suite. |
| OutboxRetryIntegrationTest | Backoff/budget, FAILED retention, stale/concurrent recovery and invalid payload handling. |
| TransactionHistoryEventProcessorIntegrationTest | Atomic marker/movements, duplicate no-op, concurrent conflict waiting, rollback waiter and partial pair rejection. |
| TransferEventsKafkaListenerIntegrationTest | Real Kafka/PostgreSQL success/offsets, nested transient retry, malformed/legacy DLT, exhaustion and failed DLT send. |
| TransferEventsErrorHandlingTest | DLT ACK wait, synchronous/asynchronous send failure, source seek and nested retry classification. |
| TransactionHistoryEndToEndIntegrationTest | Real service → Outbox → Kafka → History, INTERNAL/EXTERNAL, duplicate and simulated post-ACK/pre-result crash window. No real process kill. |

### Documentation validation

VALIDATION_REPORT

## Glossary

| Term | Meaning in FluxBank |
|---|---|
| Outbox | Durable publication intent inserted in the financial transaction. |
| Kafka | Partitioned event log carrying completion snapshots; not the balance ledger. |
| ACK | Broker acceptance of a send; not History completion. |
| Key / partition | Key-based routing; order is partition-local, not global/account-wide. |
| Consumer group | Consumers sharing source partitions and committed offsets. |
| Offset | Position in one topic partition; committed offset generally identifies the next record. |
| Idempotency | Redelivering the same event produces no additional History effect. |
| Retry | Policy-controlled new attempt after a failure. |
| Redelivery | Reading the same Kafka record again after seek, crash or uncommitted offset. |
| Recovery | Releasing an expired processing claim into backoff/FAILED, or moving a failed consumer record to DLT. |
| Backoff | Delay before retry; exponential for Outbox, fixed for consumer. |
| DLT / DLQ | Error topic; property uses dlq, default name ends .DLT. |
| Atomicity | All enclosed PostgreSQL writes commit together or roll back together. |
| Fencing | Matching status/attempts/claimedAt before accepting an old worker's DB result. |

## Source index

| Identifier | Source |
|---|---|
| `Account` | [src/main/java/com/fluxbank/account/domain/Account.java:18](../../src/main/java/com/fluxbank/account/domain/Account.java#L18) |
| `Account.validateAvailableForCredit()` | [src/main/java/com/fluxbank/account/domain/Account.java:212](../../src/main/java/com/fluxbank/account/domain/Account.java#L212) |
| `Account.validateAvailableForDebit()` | [src/main/java/com/fluxbank/account/domain/Account.java:204](../../src/main/java/com/fluxbank/account/domain/Account.java#L204) |
| `AccountBalance` | [src/main/java/com/fluxbank/account/domain/AccountBalance.java:14](../../src/main/java/com/fluxbank/account/domain/AccountBalance.java#L14) |
| `AccountBalance.credit()` | [src/main/java/com/fluxbank/account/domain/AccountBalance.java:101](../../src/main/java/com/fluxbank/account/domain/AccountBalance.java#L101) |
| `AccountBalance.debit()` | [src/main/java/com/fluxbank/account/domain/AccountBalance.java:89](../../src/main/java/com/fluxbank/account/domain/AccountBalance.java#L89) |
| `AccountBalance.release()` | [src/main/java/com/fluxbank/account/domain/AccountBalance.java:125](../../src/main/java/com/fluxbank/account/domain/AccountBalance.java#L125) |
| `AccountBalance.reserve()` | [src/main/java/com/fluxbank/account/domain/AccountBalance.java:113](../../src/main/java/com/fluxbank/account/domain/AccountBalance.java#L113) |
| `AccountBalance.settle()` | [src/main/java/com/fluxbank/account/domain/AccountBalance.java:137](../../src/main/java/com/fluxbank/account/domain/AccountBalance.java#L137) |
| `AccountBalanceRepository.findAllByAccountPublicIdInForUpdate()` | [src/main/java/com/fluxbank/account/repository/AccountBalanceRepository.java:29](../../src/main/java/com/fluxbank/account/repository/AccountBalanceRepository.java#L29) |
| `AccountBalanceRepository.findByAccountPublicIdForUpdate()` | [src/main/java/com/fluxbank/account/repository/AccountBalanceRepository.java:39](../../src/main/java/com/fluxbank/account/repository/AccountBalanceRepository.java#L39) |
| `AccountRepository.findByIban()` | [src/main/java/com/fluxbank/account/repository/AccountRepository.java:51](../../src/main/java/com/fluxbank/account/repository/AccountRepository.java#L51) |
| `AccountRepository.findByPublicId()` | [src/main/java/com/fluxbank/account/repository/AccountRepository.java:32](../../src/main/java/com/fluxbank/account/repository/AccountRepository.java#L32) |
| `AccountRepository.findByPublicIdAndUser()` | [src/main/java/com/fluxbank/account/repository/AccountRepository.java:46](../../src/main/java/com/fluxbank/account/repository/AccountRepository.java#L46) |
| `BankTransferRequest` | [src/main/java/com/fluxbank/transfer/infrastructure/BankTransferRequest.java:7](../../src/main/java/com/fluxbank/transfer/infrastructure/BankTransferRequest.java#L7) |
| `BeneficiaryRepository.findByPublicIdAndOwnerAndActiveTrue()` | [src/main/java/com/fluxbank/beneficiary/repository/BeneficiaryRepository.java:23](../../src/main/java/com/fluxbank/beneficiary/repository/BeneficiaryRepository.java#L23) |
| `BeneficiaryService` | [src/main/java/com/fluxbank/beneficiary/application/BeneficiaryService.java:21](../../src/main/java/com/fluxbank/beneficiary/application/BeneficiaryService.java#L21) |
| `CreateTransferRequest` | [src/main/java/com/fluxbank/transfer/api/CreateTransferRequest.java:10](../../src/main/java/com/fluxbank/transfer/api/CreateTransferRequest.java#L10) |
| `CustomUserDetailsService.loadUserByUsername()` | [src/main/java/com/fluxbank/common/security/CustomUserDetailsService.java:21](../../src/main/java/com/fluxbank/common/security/CustomUserDetailsService.java#L21) |
| `ExternalTransferFinalizationService.complete()` | [src/main/java/com/fluxbank/transfer/application/ExternalTransferFinalizationService.java:35](../../src/main/java/com/fluxbank/transfer/application/ExternalTransferFinalizationService.java#L35) |
| `ExternalTransferFinalizationService.fail()` | [src/main/java/com/fluxbank/transfer/application/ExternalTransferFinalizationService.java:57](../../src/main/java/com/fluxbank/transfer/application/ExternalTransferFinalizationService.java#L57) |
| `ExternalTransferFinalizationService.validateExternalPending()` | [src/main/java/com/fluxbank/transfer/application/ExternalTransferFinalizationService.java:73](../../src/main/java/com/fluxbank/transfer/application/ExternalTransferFinalizationService.java#L73) |
| `ExternalTransferProcessor.process()` | [src/main/java/com/fluxbank/transfer/application/provider/ExternalTransferProcessor.java:20](../../src/main/java/com/fluxbank/transfer/application/provider/ExternalTransferProcessor.java#L20) |
| `ExternalTransferReservationService.createPendingAndReserve()` | [src/main/java/com/fluxbank/transfer/application/ExternalTransferReservationService.java:36](../../src/main/java/com/fluxbank/transfer/application/ExternalTransferReservationService.java#L36) |
| `GlobalExceptionHandler` | [src/main/java/com/fluxbank/auth/exception/GlobalExceptionHandler.java:22](../../src/main/java/com/fluxbank/auth/exception/GlobalExceptionHandler.java#L22) |
| `IbanValidator` | [src/main/java/com/fluxbank/common/iban/IbanValidator.java:9](../../src/main/java/com/fluxbank/common/iban/IbanValidator.java#L9) |
| `InternalTransferProcessor.process()` | [src/main/java/com/fluxbank/transfer/application/InternalTransferProcessor.java:37](../../src/main/java/com/fluxbank/transfer/application/InternalTransferProcessor.java#L37) |
| `JwtAuthenticationFilter.doFilterInternal()` | [src/main/java/com/fluxbank/common/security/JwtAuthenticationFilter.java:30](../../src/main/java/com/fluxbank/common/security/JwtAuthenticationFilter.java#L30) |
| `JwtService.extractUsername()` | [src/main/java/com/fluxbank/common/security/JwtService.java:59](../../src/main/java/com/fluxbank/common/security/JwtService.java#L59) |
| `MockBankPaymentProviderClient.sendPayment()` | [src/main/java/com/fluxbank/transfer/infrastructure/MockBankPaymentProviderClient.java:36](../../src/main/java/com/fluxbank/transfer/infrastructure/MockBankPaymentProviderClient.java#L36) |
| `OutboxAttempt.from()` | [src/main/java/com/fluxbank/outbox/application/OutboxAttempt.java:10](../../src/main/java/com/fluxbank/outbox/application/OutboxAttempt.java#L10) |
| `OutboxClaimService.claimBatch()` | [src/main/java/com/fluxbank/outbox/application/OutboxClaimService.java:22](../../src/main/java/com/fluxbank/outbox/application/OutboxClaimService.java#L22) |
| `OutboxEvent` | [src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java:16](../../src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java#L16) |
| `OutboxEvent.claim()` | [src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java:82](../../src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java#L82) |
| `OutboxEvent.create()` | [src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java:61](../../src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java#L61) |
| `OutboxEvent.markProcessed()` | [src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java:90](../../src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java#L90) |
| `OutboxEvent.recordFailure()` | [src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java:105](../../src/main/java/com/fluxbank/outbox/domain/OutboxEvent.java#L105) |
| `OutboxEventRepository.findPendingForClaim()` | [src/main/java/com/fluxbank/outbox/repository/OutboxEventRepository.java:30](../../src/main/java/com/fluxbank/outbox/repository/OutboxEventRepository.java#L30) |
| `OutboxFailureService.recordFailure()` | [src/main/java/com/fluxbank/outbox/application/OutboxFailureService.java:20](../../src/main/java/com/fluxbank/outbox/application/OutboxFailureService.java#L20) |
| `OutboxPoller.poll()` | [src/main/java/com/fluxbank/outbox/application/OutboxPoller.java:32](../../src/main/java/com/fluxbank/outbox/application/OutboxPoller.java#L32) |
| `OutboxProcessingTransaction.markProcessed()` | [src/main/java/com/fluxbank/outbox/application/OutboxProcessingTransaction.java:19](../../src/main/java/com/fluxbank/outbox/application/OutboxProcessingTransaction.java#L19) |
| `OutboxProcessor.process()` | [src/main/java/com/fluxbank/outbox/application/OutboxProcessor.java:27](../../src/main/java/com/fluxbank/outbox/application/OutboxProcessor.java#L27) |
| `OutboxRecoveryScheduler.recover()` | [src/main/java/com/fluxbank/outbox/application/OutboxRecoveryScheduler.java:24](../../src/main/java/com/fluxbank/outbox/application/OutboxRecoveryScheduler.java#L24) |
| `OutboxRecoveryService.recoverStaleBatch()` | [src/main/java/com/fluxbank/outbox/application/OutboxRecoveryService.java:23](../../src/main/java/com/fluxbank/outbox/application/OutboxRecoveryService.java#L23) |
| `OutboxRetryPolicy.delay()` | [src/main/java/com/fluxbank/outbox/application/OutboxRetryPolicy.java:35](../../src/main/java/com/fluxbank/outbox/application/OutboxRetryPolicy.java#L35) |
| `OutboxRetryPolicy.isRetryable()` | [src/main/java/com/fluxbank/outbox/application/OutboxRetryPolicy.java:49](../../src/main/java/com/fluxbank/outbox/application/OutboxRetryPolicy.java#L49) |
| `OutboxTransferEventPublisher.publishCompleted()` | [src/main/java/com/fluxbank/transfer/infrastructure/OutboxTransferEventPublisher.java:32](../../src/main/java/com/fluxbank/transfer/infrastructure/OutboxTransferEventPublisher.java#L32) |
| `PaymentProviderConfig` | [src/main/java/com/fluxbank/transfer/infrastructure/PaymentProviderConfig.java:9](../../src/main/java/com/fluxbank/transfer/infrastructure/PaymentProviderConfig.java#L9) |
| `ProcessedEvent` | [src/main/java/com/fluxbank/processed/domain/ProcessedEvent.java:14](../../src/main/java/com/fluxbank/processed/domain/ProcessedEvent.java#L14) |
| `ProcessedEventRepository.insertIfAbsent()` | [src/main/java/com/fluxbank/processed/repository/ProcessedEventRepository.java:18](../../src/main/java/com/fluxbank/processed/repository/ProcessedEventRepository.java#L18) |
| `ReservedExternalTransfer` | [src/main/java/com/fluxbank/transfer/api/ReservedExternalTransfer.java:6](../../src/main/java/com/fluxbank/transfer/api/ReservedExternalTransfer.java#L6) |
| `SecurityConfig.securityFilterChain()` | [src/main/java/com/fluxbank/common/security/SecurityConfig.java:27](../../src/main/java/com/fluxbank/common/security/SecurityConfig.java#L27) |
| `Transaction` | [src/main/java/com/fluxbank/transaction/domain/Transaction.java:17](../../src/main/java/com/fluxbank/transaction/domain/Transaction.java#L17) |
| `Transaction.createTransferMovement()` | [src/main/java/com/fluxbank/transaction/domain/Transaction.java:59](../../src/main/java/com/fluxbank/transaction/domain/Transaction.java#L59) |
| `TransactionHistoryEventProcessor.process()` | [src/main/java/com/fluxbank/transaction/application/TransactionHistoryEventProcessor.java:20](../../src/main/java/com/fluxbank/transaction/application/TransactionHistoryEventProcessor.java#L20) |
| `TransactionHistoryService.completedTransfer()` | [src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java:68](../../src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java#L68) |
| `TransactionHistoryService.createForSnapshot()` | [src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java:55](../../src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java#L55) |
| `TransactionHistoryService.createMovement()` | [src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java:112](../../src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java#L112) |
| `TransactionHistoryService.createMovements()` | [src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java:78](../../src/main/java/com/fluxbank/transaction/application/TransactionHistoryService.java#L78) |
| `TransactionReferenceGenerator.nextReference()` | [src/main/java/com/fluxbank/transaction/application/TransactionReferenceGenerator.java:18](../../src/main/java/com/fluxbank/transaction/application/TransactionReferenceGenerator.java#L18) |
| `TransactionRepository.existsByTransferAndAccountAndTypeAndDirection()` | [src/main/java/com/fluxbank/transaction/repository/TransactionRepository.java:12](../../src/main/java/com/fluxbank/transaction/repository/TransactionRepository.java#L12) |
| `Transfer` | [src/main/java/com/fluxbank/transfer/domain/Transfer.java:19](../../src/main/java/com/fluxbank/transfer/domain/Transfer.java#L19) |
| `Transfer.complete()` | [src/main/java/com/fluxbank/transfer/domain/Transfer.java:207](../../src/main/java/com/fluxbank/transfer/domain/Transfer.java#L207) |
| `Transfer.create()` | [src/main/java/com/fluxbank/transfer/domain/Transfer.java:157](../../src/main/java/com/fluxbank/transfer/domain/Transfer.java#L157) |
| `Transfer.fail()` | [src/main/java/com/fluxbank/transfer/domain/Transfer.java:219](../../src/main/java/com/fluxbank/transfer/domain/Transfer.java#L219) |
| `TransferCompletedEvent` | [src/main/java/com/fluxbank/transfer/application/TransferCompletedEvent.java:12](../../src/main/java/com/fluxbank/transfer/application/TransferCompletedEvent.java#L12) |
| `TransferCompletedKafkaEvent` | [src/main/java/com/fluxbank/transfer/infrastructure/kafka/TransferCompletedKafkaEvent.java:15](../../src/main/java/com/fluxbank/transfer/infrastructure/kafka/TransferCompletedKafkaEvent.java#L15) |
| `TransferCompletedKafkaPublisher.publish()` | [src/main/java/com/fluxbank/transfer/infrastructure/kafka/TransferCompletedKafkaPublisher.java:25](../../src/main/java/com/fluxbank/transfer/infrastructure/kafka/TransferCompletedKafkaPublisher.java#L25) |
| `TransferController.createTransfer()` | [src/main/java/com/fluxbank/transfer/api/TransferController.java:21](../../src/main/java/com/fluxbank/transfer/api/TransferController.java#L21) |
| `TransferEventsConsumerConfiguration.transferEventsErrorHandler()` | [src/main/java/com/fluxbank/transaction/infrastructure/kafka/TransferEventsConsumerConfiguration.java:43](../../src/main/java/com/fluxbank/transaction/infrastructure/kafka/TransferEventsConsumerConfiguration.java#L43) |
| `TransferEventsKafkaListener.handle()` | [src/main/java/com/fluxbank/transaction/infrastructure/kafka/TransferEventsKafkaListener.java:20](../../src/main/java/com/fluxbank/transaction/infrastructure/kafka/TransferEventsKafkaListener.java#L20) |
| `TransferLifecycleService.createPending()` | [src/main/java/com/fluxbank/transfer/application/TransferLifecycleService.java:32](../../src/main/java/com/fluxbank/transfer/application/TransferLifecycleService.java#L32) |
| `TransferLifecycleService.getResponse()` | [src/main/java/com/fluxbank/transfer/application/TransferLifecycleService.java:79](../../src/main/java/com/fluxbank/transfer/application/TransferLifecycleService.java#L79) |
| `TransferLifecycleService.markFailed()` | [src/main/java/com/fluxbank/transfer/application/TransferLifecycleService.java:65](../../src/main/java/com/fluxbank/transfer/application/TransferLifecycleService.java#L65) |
| `TransferReferenceGenerator.nextReference()` | [src/main/java/com/fluxbank/transfer/application/TransferReferenceGenerator.java:18](../../src/main/java/com/fluxbank/transfer/application/TransferReferenceGenerator.java#L18) |
| `TransferRepository.findByPublicIdForUpdate()` | [src/main/java/com/fluxbank/transfer/repository/TransferRepository.java:26](../../src/main/java/com/fluxbank/transfer/repository/TransferRepository.java#L26) |
| `TransferResponse` | [src/main/java/com/fluxbank/transfer/api/TransferResponse.java:12](../../src/main/java/com/fluxbank/transfer/api/TransferResponse.java#L12) |
| `TransferService.createTransfer()` | [src/main/java/com/fluxbank/transfer/application/TransferService.java:59](../../src/main/java/com/fluxbank/transfer/application/TransferService.java#L59) |
| `TransferService.processExternalTransfer()` | [src/main/java/com/fluxbank/transfer/application/TransferService.java:169](../../src/main/java/com/fluxbank/transfer/application/TransferService.java#L169) |
| `TransferService.processInternalTransfer()` | [src/main/java/com/fluxbank/transfer/application/TransferService.java:104](../../src/main/java/com/fluxbank/transfer/application/TransferService.java#L104) |
| `UserRepository.findByEmail()` | [src/main/java/com/fluxbank/customer/repository/UserRepository.java:16](../../src/main/java/com/fluxbank/customer/repository/UserRepository.java#L16) |

### Migrations and configuration

- [V1__create_initialize_database.sql](../../src/main/resources/db/migration/V1__create_initialize_database.sql#L1)
- [V2__create_users_table.sql](../../src/main/resources/db/migration/V2__create_users_table.sql#L1)
- [V3__make_email_unique_case_insensitive.sql](../../src/main/resources/db/migration/V3__make_email_unique_case_insensitive.sql#L1)
- [V4__add_customer_number.sql](../../src/main/resources/db/migration/V4__add_customer_number.sql#L1)
- [V5__create_accounts_table.sql](../../src/main/resources/db/migration/V5__create_accounts_table.sql#L1)
- [V6__add_iban_account_identifier.sql](../../src/main/resources/db/migration/V6__add_iban_account_identifier.sql#L1)
- [V7__create_beneficiaries_table.sql](../../src/main/resources/db/migration/V7__create_beneficiaries_table.sql#L1)
- [V8__create_transfers_table.sql](../../src/main/resources/db/migration/V8__create_transfers_table.sql#L1)
- [V9__create_balance_and_migrate.sql](../../src/main/resources/db/migration/V9__create_balance_and_migrate.sql#L1)
- [V10__create_transactions_table.sql](../../src/main/resources/db/migration/V10__create_transactions_table.sql#L1)
- [V11__create_outbox_event_table.sql](../../src/main/resources/db/migration/V11__create_outbox_event_table.sql#L1)
- [V12__add_outbox_claiming.sql](../../src/main/resources/db/migration/V12__add_outbox_claiming.sql#L1)
- [V13__add_outbox_retry_timing.sql](../../src/main/resources/db/migration/V13__add_outbox_retry_timing.sql#L1)
- [V14__create_processed_event_table.sql](../../src/main/resources/db/migration/V14__create_processed_event_table.sql#L1)
- [src/main/resources/application.yml](../../src/main/resources/application.yml#L1)
- [src/test/resources/application-integration.yml](../../src/test/resources/application-integration.yml#L1)
- [compose.yaml](../../compose.yaml#L1)
- [docker/mockbank/src/routes/transfer_routes.py](../../docker/mockbank/src/routes/transfer_routes.py#L1)

### Integration and error-handler tests

- [TransferTransactionsIntegrationTest](../../src/test/java/com/fluxbank/integration/TransferTransactionsIntegrationTest.java#L1)
- [PostgresConcurrencyIntegrationTest](../../src/test/java/com/fluxbank/integration/PostgresConcurrencyIntegrationTest.java#L1)
- [OutboxClaimIntegrationTest](../../src/test/java/com/fluxbank/integration/OutboxClaimIntegrationTest.java#L1)
- [OutboxProcessorIntegrationTest](../../src/test/java/com/fluxbank/integration/OutboxProcessorIntegrationTest.java#L1)
- [OutboxRetryIntegrationTest](../../src/test/java/com/fluxbank/integration/OutboxRetryIntegrationTest.java#L1)
- [TransactionHistoryEventProcessorIntegrationTest](../../src/test/java/com/fluxbank/integration/TransactionHistoryEventProcessorIntegrationTest.java#L1)
- [TransferEventsKafkaListenerIntegrationTest](../../src/test/java/com/fluxbank/integration/TransferEventsKafkaListenerIntegrationTest.java#L1)
- [TransferEventsErrorHandlingTest](../../src/test/java/com/fluxbank/transaction/infrastructure/kafka/TransferEventsErrorHandlingTest.java#L1)
- [TransactionHistoryEndToEndIntegrationTest](../../src/test/java/com/fluxbank/integration/TransactionHistoryEndToEndIntegrationTest.java#L1)
