package com.fluxbank.transfer.domain;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TransferTest {
    private final Account source = Account.create(null, "source", "source-iban", 1L, 1, CurrencyCode.EUR);
    private final Account destination = Account.create(null, "destination", "destination-iban", 2L, 2, CurrencyCode.EUR);

    private Transfer pendingTransfer() {
        return Transfer.create("TRX-reference", null, source, null, destination, "Ada Lovelace",
                destination.getIban(), "FluxBank", BigDecimal.TEN, CurrencyCode.EUR, TransferType.INTERNAL);
    }

    @Test
    void pendingTransferCanComplete() {
        Transfer transfer = pendingTransfer();
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());

        transfer.complete();

        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        assertNotNull(transfer.getCompletedAt());
    }

    @Test
    void pendingTransferCanFailWithSuppliedReason() {
        Transfer transfer = pendingTransfer();
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());

        transfer.fail(TransferFailureReason.INSUFFICIENT_FUNDS);

        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(TransferFailureReason.INSUFFICIENT_FUNDS, transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, true", "COMPLETED, false", "FAILED, true", "FAILED, false"})
    void terminalStateCannotBeReplaced(TransferStatus terminalStatus, boolean complete) {
        Transfer transfer = pendingTransfer();
        if (terminalStatus == TransferStatus.COMPLETED) {
            transfer.complete();
        } else {
            transfer.fail(TransferFailureReason.INSUFFICIENT_FUNDS);
        }
        TransferFailureReason originalReason = transfer.getFailureReason();
        Instant originalCompletedAt = transfer.getCompletedAt();

        assertThrows(IllegalStateException.class, () -> {
            if (complete) {
                transfer.complete();
            } else {
                transfer.fail(TransferFailureReason.CURRENCY_MISMATCH);
            }
        });

        assertEquals(terminalStatus, transfer.getStatus());
        assertEquals(originalReason, transfer.getFailureReason());
        assertEquals(originalCompletedAt, transfer.getCompletedAt());
    }

    @Test
    void nullFailureReasonIsRejectedWithoutChangingPendingState() {
        Transfer transfer = pendingTransfer();

        assertThrows(IllegalArgumentException.class, () -> transfer.fail(null));

        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());
    }

    @ParameterizedTest
    @EnumSource(TransferType.class)
    void destinationMustMatchTransferType(TransferType type) {
        Account invalidDestination = type == TransferType.INTERNAL ? null : destination;

        assertThrows(IllegalArgumentException.class, () -> Transfer.create(
                "ref", null, source, null, invalidDestination, "Ada Lovelace", destination.getIban(),
                "Bank", BigDecimal.TEN, CurrencyCode.EUR, type));
    }
}
