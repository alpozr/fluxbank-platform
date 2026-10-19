package com.fluxbank.transfer.application.provider;

import com.fluxbank.transfer.api.ReservedExternalTransfer;
import com.fluxbank.transfer.application.ExternalTransferFinalizationService;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferFailureReason;
import org.springframework.stereotype.Service;

@Service
public class ExternalTransferProcessor {

    private final PaymentProviderClient paymentProviderClient;
    private final ExternalTransferFinalizationService externalTransferFinalizationService;

    public ExternalTransferProcessor(PaymentProviderClient paymentProviderClient, ExternalTransferFinalizationService externalTransferFinalizationService) {
        this.paymentProviderClient = paymentProviderClient;
        this.externalTransferFinalizationService = externalTransferFinalizationService;
    }

    public void process(ReservedExternalTransfer transfer){
        PaymentProviderRequest request =
                new PaymentProviderRequest(
                        transfer.referenceNumber(),
                        transfer.beneficiaryName(),
                        transfer.destinationIban(),
                        transfer.destinationBankName(),
                        transfer.amount(),
                        transfer.currency()
                );
        PaymentProviderResult providerResult=paymentProviderClient.sendPayment(request);

        if (providerResult.status() == PaymentProviderStatus.SUCCESS) {

            externalTransferFinalizationService.complete(
                    transfer.publicId()
            );

            return;
        }

        if (providerResult.status() == PaymentProviderStatus.REJECTED) {

            externalTransferFinalizationService.fail(
                    transfer.publicId(),
                    TransferFailureReason.EXTERNAL_PROVIDER_REJECTED
            );
        }
        //unknown,timeout vs pending + reserved kalır.reconcilation/status cozecek

    }
}
