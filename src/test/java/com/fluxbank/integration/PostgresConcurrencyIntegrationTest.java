package com.fluxbank.integration;

import com.fluxbank.account.domain.exception.InsufficientFundsException;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.transfer.api.CreateTransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class PostgresConcurrencyIntegrationTest extends DatabaseIntegrationTestSupport {
    private ExecutorService workers;

    @BeforeEach
    void startWorkers() {
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void finishWorkersBeforeCommittedCleanup() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "Workers must finish before cleanup");
        }
    }

    @Test
    void pessimisticBalanceLockBlocksCompetingMutationUntilCommit() throws Exception {
        Fixture f = fixture("1000", "500");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger waitingBackend = new AtomicInteger();
        Future<?> first = workers.submit(() -> tx(() -> {
            var balance = balances.findByAccountPublicIdForUpdate(f.source().getPublicId()).orElseThrow();
            balance.debit(new BigDecimal("20"));
            entityManager.flush();
            locked.countDown();
            awaitLatch(release);
            return null;
        }));
        Future<?> second;
        try {
            awaitLatch(locked);
            second = workers.submit(() -> tx(() -> {
                waitingBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                balances.findByAccountPublicIdForUpdate(f.source().getPublicId()).orElseThrow()
                        .debit(new BigDecimal("10"));
                return null;
            }));
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'",
                    Integer.class, waitingBackend.get()) == 1);
            assertFalse(second.isDone(), "PostgreSQL has reported that TX-B is waiting for TX-A's row lock");
            assertBalance(f.source(), "1000", "0", "1000"); // Other connections cannot see TX-A's uncommitted debit.
        } finally {
            release.countDown();
        }
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);
        assertBalance(f.source(), "970", "0", "970");
    }

    @Test
    void multiAccountLockQueryReturnsAccountIdOrderRegardlessOfRequestedOrder() {
        Fixture f = fixture("1000", "500");
        var actual = tx(() -> balances.findAllByAccountPublicIdInForUpdate(
                        List.of(f.destination().getPublicId(), f.source().getPublicId()))
                .stream().map(b -> b.getAccount().getPublicId()).toList());
        var expected = jdbc.queryForList(
                "SELECT public_id FROM fluxbank.accounts WHERE public_id IN (?, ?) ORDER BY id",
                java.util.UUID.class, f.source().getPublicId(), f.destination().getPublicId());

        assertEquals(expected, actual);
        // This proves the repository's ordering contract, not absence of all possible deadlocks.
    }

    @Test
    void concurrentTransfersCannotBothSpendEightyFromOneHundred() throws Exception {
        Fixture f = fixture("100", "500");
        var request = new CreateTransferRequest(f.source().getPublicId(),
                f.internalBeneficiary().getPublicId(), new BigDecimal("80"));
        List<String> outcomes = runTogether(() -> {
            try {
                return transferService.createTransfer(f.owner().getEmail(), request).status().name();
            } catch (InsufficientFundsException expected) {
                return "INSUFFICIENT_FUNDS";
            }
        });

        assertEquals(1, outcomes.stream().filter("COMPLETED"::equals).count());
        assertEquals(1, outcomes.stream().filter("INSUFFICIENT_FUNDS"::equals).count());
        assertBalance(f.source(), "20", "0", "20");
        assertBalance(f.destination(), "580", "0", "580");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM fluxbank.transfers WHERE status='COMPLETED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM fluxbank.transfers
                WHERE status='FAILED' AND failure_reason='INSUFFICIENT_FUNDS'
                """, Integer.class));
        assertEquals(0L, transactions.count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fluxbank.outbox_event", Integer.class));
        assertEquals(0, new BigDecimal("80").compareTo(jdbc.queryForObject(
                "SELECT sum(amount) FROM fluxbank.transfers WHERE status='COMPLETED'", BigDecimal.class)));
    }

    @Test
    void concurrentFinalizersCannotSettleSameReservationTwice() throws Exception {
        Fixture f = fixture("1000", "500");
        reserveOtherFunds(f, "200");
        var id = externalPending(f, "300");

        List<Boolean> outcomes = runTogether(() -> {
            try {
                finalization.complete(id);
                return true;
            } catch (IllegalStateException expected) {
                return false;
            }
        });

        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertBalance(f.source(), "700", "200", "500");
        assertEquals("COMPLETED", state(id).get("status"));
        assertTrue(history(id).isEmpty());
        assertEquals(1, outbox(id).size());
    }

    @Test
    void concurrentBeneficiaryInsertsCannotPersistDuplicateOwnerIban() throws Exception {
        Fixture f = fixture("1000", "500");
        String iban = ibans.generate(tx(() -> accounts.getNextIbanAccountIdentifier()));
        CountDownLatch checkedAbsent = new CountDownLatch(2);
        CountDownLatch insert = new CountDownLatch(1);
        Supplier<Boolean> attempt = () -> {
            try {
                return tx(() -> {
                    assertTrue(beneficiaries.findByOwnerAndIban(f.owner(), iban).isEmpty());
                    checkedAbsent.countDown();
                    awaitLatch(insert);
                    beneficiaries.saveAndFlush(Beneficiary.create(f.owner(), "Concurrent", "Recipient", iban, "Bank"));
                    return true;
                });
            } catch (DataIntegrityViolationException expected) {
                assertSqlState("23505", expected);
                return false;
            }
        };
        Future<Boolean> first = workers.submit(attempt::get);
        Future<Boolean> second = workers.submit(attempt::get);
        try {
            awaitLatch(checkedAbsent); // Both transactions observed the key absent before either INSERT.
        } finally {
            insert.countDown();
        }

        List<Boolean> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM fluxbank.beneficiaries b JOIN fluxbank.users u ON u.id=b.owner_user_id
                WHERE u.public_id=? AND b.iban=?
                """, Integer.class, f.owner().getPublicId(), iban));
    }

    private <T> List<T> runTogether(Supplier<T> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> task = () -> {
            ready.countDown();
            awaitLatch(start);
            return work.get();
        };
        Future<T> first = workers.submit(task);
        Future<T> second = workers.submit(task);
        try {
            awaitLatch(ready);
        } finally {
            start.countDown();
        }
        return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for coordinated transaction");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Transaction worker interrupted", failure);
        }
    }
}
