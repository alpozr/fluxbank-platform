package com.fluxbank.transfer.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxTransferEventPublisherTest {
    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final OutboxTransferEventPublisher publisher = new OutboxTransferEventPublisher(events, new ObjectMapper(), transfers);

    @ParameterizedTest
    @EnumSource(TransferType.class)
    void capturesCompletedTransferSnapshotWithOnlyPublicAccountIds(TransferType type) {
        Account source = mock(Account.class);
        UUID sourceId = UUID.randomUUID();
        when(source.getPublicId()).thenReturn(sourceId);
        when(source.getCurrency()).thenReturn(CurrencyCode.USD);
        Account destination = type == TransferType.INTERNAL ? mock(Account.class) : null;
        UUID destinationId = destination == null ? null : UUID.randomUUID();
        if (destination != null) when(destination.getPublicId()).thenReturn(destinationId);
        Transfer transfer = Transfer.create("reference", mock(User.class), source, mock(Beneficiary.class), destination,
                "Recipient", "BG80BNBG96611020345678", "Bank", new BigDecimal("123.4567"), CurrencyCode.EUR, type);
        transfer.complete();
        when(transfers.findByPublicId(transfer.getPublicId())).thenReturn(Optional.of(transfer));

        publisher.publishCompleted(transfer.getPublicId());

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(events).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertNotNull(event.getPublicId());
        assertEquals("TRANSFER_COMPLETED", event.getEventType());
        assertEquals(transfer.getPublicId(), event.getAggregateId());
        var payload = event.getPayload();
        assertEquals(6, payload.size());
        assertEquals(transfer.getPublicId().toString(), payload.get("transferPublicId").asText());
        assertEquals(type.name(), payload.get("transferType").asText());
        assertEquals(sourceId.toString(), payload.get("sourceAccountPublicId").asText());
        if (destinationId == null) assertTrue(payload.get("destinationAccountPublicId").isNull());
        else assertEquals(destinationId.toString(), payload.get("destinationAccountPublicId").asText());
        assertEquals("123.4567", payload.get("amount").asText());
        assertEquals("EUR", payload.get("currency").asText());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getProcessedAt());
        assertNull(event.getLastError());
        when(source.getPublicId()).thenReturn(UUID.randomUUID());
        assertEquals(sourceId.toString(), event.getPayload().get("sourceAccountPublicId").asText());
    }

    @Test
    void rejectsMissingTransferPublicIdWithoutSaving() {
        assertThrows(IllegalArgumentException.class, () -> publisher.publishCompleted(null));
        verifyNoInteractions(events, transfers);
    }

    @Test
    void rejectsUnknownTransferWithoutSaving() {
        UUID id = UUID.randomUUID();
        when(transfers.findByPublicId(id)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> publisher.publishCompleted(id));
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"PENDING", "FAILED"})
    void rejectsNonCompletedTransfer(TransferStatus status) {
        Transfer transfer = mock(Transfer.class);
        UUID id = UUID.randomUUID();
        when(transfers.findByPublicId(id)).thenReturn(Optional.of(transfer));
        when(transfer.getStatus()).thenReturn(status);
        assertThrows(IllegalStateException.class, () -> publisher.publishCompleted(id));
        verifyNoInteractions(events);
    }
}
