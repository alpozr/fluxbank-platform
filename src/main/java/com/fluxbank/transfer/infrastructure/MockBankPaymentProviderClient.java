package com.fluxbank.transfer.infrastructure;

import com.fluxbank.transfer.application.provider.PaymentProviderClient;
import com.fluxbank.transfer.application.provider.PaymentProviderRequest;
import com.fluxbank.transfer.application.provider.PaymentProviderResult;
import com.fluxbank.transfer.application.provider.PaymentProviderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MockBankPaymentProviderClient
        implements PaymentProviderClient {

    private final RestClient restClient;
    private final String settlementAccount;
    private final String recipientAccount;

    public MockBankPaymentProviderClient(
            RestClient paymentProviderRestClient,
            @Value("${payment-provider.settlement-account}")
                    String settlementAccount,
            @Value("${payment-provider.recipient-account}")
                    String recipientAccount
    ) {
        this.restClient = paymentProviderRestClient;
        this.settlementAccount = settlementAccount;
        this.recipientAccount = recipientAccount;
    }

    @Override
    public PaymentProviderResult sendPayment(
            PaymentProviderRequest request
    ) {

        BankTransferRequest providerRequest =
                new BankTransferRequest(
                        request.referenceNumber(),
                        settlementAccount,
                        recipientAccount,
                        request.amount(),
                        request.currency(),
                        request.referenceNumber()
                );

        try {

            MockBankTransferResponse response =
                    restClient.post()
                            .uri("/idempotent-transfers")
                            .body(providerRequest)
                            .retrieve()
                            .body(MockBankTransferResponse.class);

            if (response == null) {
                return unknown();
            }

            if (!"completed".equalsIgnoreCase(response.status())) {
                return unknown();
            }

            return new PaymentProviderResult(
                    PaymentProviderStatus.SUCCESS,
                    response.transactionReference()
            );

        } catch (HttpClientErrorException ex) {

            return new PaymentProviderResult(
                    PaymentProviderStatus.REJECTED,
                    null
            );

        } catch (HttpServerErrorException ex) {

            return unknown();

        } catch (ResourceAccessException ex) {

            return unknown();
        } catch (RestClientException ex) {

            // RestClient wraps response decoding failures; completion cannot be confirmed.
            return unknown();
        }
    }

    private PaymentProviderResult unknown() {
        return new PaymentProviderResult(
                PaymentProviderStatus.UNKNOWN,
                null
        );
    }
}
