package com.fluxbank.transfer.application.provider;

import com.fluxbank.transfer.api.ReservedExternalTransfer;
import com.fluxbank.transfer.application.ExternalTransferFinalizationService;
import com.fluxbank.transfer.domain.TransferFailureReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ExternalTransferProcessorTest {
    private final PaymentProviderClient provider = mock(PaymentProviderClient.class);
    private final ExternalTransferFinalizationService finalization = mock(ExternalTransferFinalizationService.class);
    private final ExternalTransferProcessor processor = new ExternalTransferProcessor(provider, finalization);

    private ReservedExternalTransfer reserved() {
        return new ReservedExternalTransfer(UUID.randomUUID(), "ref", "name", "iban", "bank",
                BigDecimal.TEN, "EUR");
    }

    @Test
    void successUsesCompletePath() {
        var transfer = reserved();
        when(provider.sendPayment(any())).thenReturn(new PaymentProviderResult(PaymentProviderStatus.SUCCESS, "provider-ref"));
        processor.process(transfer);
        verify(finalization).complete(transfer.publicId());
        verifyNoMoreInteractions(finalization);
    }

    @Test
    void rejectionUsesFailurePath() {
        var transfer = reserved();
        when(provider.sendPayment(any())).thenReturn(new PaymentProviderResult(PaymentProviderStatus.REJECTED, "provider-ref"));
        processor.process(transfer);
        verify(finalization).fail(transfer.publicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
        verifyNoMoreInteractions(finalization);
    }

    @Test
    void unknownLeavesPendingReservationUntouched() {
        when(provider.sendPayment(any())).thenReturn(new PaymentProviderResult(PaymentProviderStatus.UNKNOWN, null));
        processor.process(reserved());
        verifyNoInteractions(finalization);
    }
}
