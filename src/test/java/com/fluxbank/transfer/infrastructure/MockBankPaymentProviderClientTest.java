package com.fluxbank.transfer.infrastructure;

import com.fluxbank.transfer.application.provider.PaymentProviderClient;
import com.fluxbank.transfer.application.provider.PaymentProviderRequest;
import com.fluxbank.transfer.application.provider.PaymentProviderResult;
import com.fluxbank.transfer.application.provider.PaymentProviderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MockBankPaymentProviderClientTest {
    private MockRestServiceServer server;
    private PaymentProviderClient client;
    private final String baseUrl = "http://mockbank.test";
    private final PaymentProviderRequest request = new PaymentProviderRequest(
            "TRX-20261022-100001", "Ada Lovelace", "beneficiary-iban", "Beneficiary Bank",
            new BigDecimal("300.25"), "EUR");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MockBankPaymentProviderClient(builder.build(), "FLOAT-MAIN-001", "EXT-CUST-001");
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "COMPLETED"})
    void completedPaymentReturnsSuccessAndProviderReference(String status) {
        respond(withSuccess("""
                {"status":"%s","transaction_reference":"mockbank-payment-123"}
                """.formatted(status), MediaType.APPLICATION_JSON));

        PaymentProviderResult result = client.sendPayment(request);

        assertEquals(PaymentProviderStatus.SUCCESS, result.status());
        assertEquals("mockbank-payment-123", result.providerReference());
    }

    @Test
    void successfulHttpResponseWithPendingPaymentIsUnknown() {
        respond(withSuccess("""
                {"status":"pending","transaction_reference":"mockbank-payment-123"}
                """, MediaType.APPLICATION_JSON));

        assertUnknown(client.sendPayment(request));
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNPROCESSABLE_ENTITY"})
    void clientRejectionReturnsRejected(HttpStatus status) {
        respond(withStatus(status));

        PaymentProviderResult result = client.sendPayment(request);

        assertEquals(PaymentProviderStatus.REJECTED, result.status());
        assertNull(result.providerReference());
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "SERVICE_UNAVAILABLE"})
    void serverFailureHasUnknownFinancialOutcome(HttpStatus status) {
        respond(withStatus(status));

        assertUnknown(client.sendPayment(request));
    }

    @Test
    void connectionFailureHasUnknownFinancialOutcome() {
        respond(withException(new ConnectException("Connection refused")));

        assertUnknown(client.sendPayment(request));
    }

    @Test
    void timeoutHasUnknownFinancialOutcome() {
        respond(withException(new SocketTimeoutException("Read timed out")));

        assertUnknown(client.sendPayment(request));
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"OK", "NO_CONTENT"})
    void emptySuccessfulResponseIsUnknown(HttpStatus status) {
        respond(withStatus(status));

        assertUnknown(client.sendPayment(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"transaction_reference\":\"mockbank-payment-123\"}",
            "{\"status\":null}",
            "{\"status\":\"\"}"
    })
    void incompleteResponseCannotProveCompletion(String body) {
        respond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertUnknown(client.sendPayment(request));
    }

    @Test
    void malformedJsonHasUnknownFinancialOutcome() {
        respond(withSuccess("{\"status\":", MediaType.APPLICATION_JSON));

        assertUnknown(client.sendPayment(request));
    }

    @Test
    void mapsSupportedRequestFieldsAndReusesTransferReferenceAsIdempotencyKey() {
        server.expect(twice(), requestTo(baseUrl + "/idempotent-transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "idempotency_key": "TRX-20261022-100001",
                          "from_account": "FLOAT-MAIN-001",
                          "to_account": "EXT-CUST-001",
                          "amount": 300.25,
                          "currency": "EUR",
                          "description": "TRX-20261022-100001"
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"status":"completed","transaction_reference":"mockbank-payment-123"}
                        """, MediaType.APPLICATION_JSON));

        PaymentProviderResult first = client.sendPayment(request);
        PaymentProviderResult repeated = client.sendPayment(request);

        assertEquals(PaymentProviderStatus.SUCCESS, first.status());
        assertEquals(first, repeated);
    }

    private void respond(ResponseCreator response) {
        server.expect(requestTo(baseUrl + "/idempotent-transfers"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(response);
    }

    private void assertUnknown(PaymentProviderResult result) {
        assertEquals(PaymentProviderStatus.UNKNOWN, result.status());
        assertNull(result.providerReference());
    }
}
