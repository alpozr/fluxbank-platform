package com.fluxbank.transfer.api;

import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.account.domain.exception.*;
import com.fluxbank.auth.exception.GlobalExceptionHandler;
import com.fluxbank.beneficiary.exception.BeneficiaryNotFoundException;
import com.fluxbank.transfer.application.TransferService;
import com.fluxbank.transfer.application.exception.*;
import com.fluxbank.transfer.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransferControllerTest {
    private TransferService service;
    private MockMvc mvc;
    private final String email = "owner@example.com";
    private final UUID sourceId = UUID.randomUUID();
    private final UUID beneficiaryId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken principal =
            new UsernamePasswordAuthenticationToken(email, "unused", List.of());

    @BeforeEach
    void setUp() {
        service = mock(TransferService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new SourceAccountNotFoundException(), 404, "Source account not found", "TRANSFER_SOURCE_ACCOUNT_NOT_FOUND"),
                Arguments.of(new BeneficiaryNotFoundException(), 404, "Beneficiary not found", null),
                Arguments.of(new BeneficiaryNotFluxBankAccountException(), 422, "Invalid transfer destination", "TRANSFER_DESTINATION_NOT_SUPPORTED"),
                Arguments.of(new SameAccountTransferException(), 422, "Invalid transfer", "TRANSFER_SAME_ACCOUNT"),
                Arguments.of(new InsufficientFundsException("Insufficient account balance"), 422, "Insufficient funds", "TRANSFER_INSUFFICIENT_FUNDS"),
                Arguments.of(new AccountNotAvailableForDebitException("Account cannot be debited"), 409, "Source account unavailable", "TRANSFER_SOURCE_ACCOUNT_UNAVAILABLE"),
                Arguments.of(new AccountNotAvailableForCreditException("Account cannot be credited"), 409, "Destination account unavailable", "TRANSFER_DESTINATION_ACCOUNT_UNAVAILABLE"),
                Arguments.of(new CurrencyMismatchException("Source and destination account currencies must match"), 422, "Currency mismatch", "TRANSFER_CURRENCY_MISMATCH")
        );
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void shouldReturnProblemDetailForBusinessFailure(RuntimeException exception, int status, String title, String errorCode) throws Exception {
        when(service.createTransfer(eq(email), any())).thenThrow(exception);

        var result = mvc.perform(post("/api/v1/transfers").principal(principal)
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson()))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("status").value(status))
                .andExpect(jsonPath("title").value(title))
                .andExpect(jsonPath("detail").value(exception.getMessage()));
        if (errorCode != null) result.andExpect(jsonPath("errorCode").value(errorCode));
    }

    @Test
    void shouldReturn201AndTransferResponseOnSuccess() throws Exception {
        var publicId = UUID.randomUUID();
        var response = new TransferResponse(publicId, "TR-1", sourceId, "Ada Lovelace",
                "BG08FLUX06110000000001", "FluxBank", new BigDecimal("10.00"), CurrencyCode.EUR,
                TransferType.INTERNAL, TransferStatus.COMPLETED, null, Instant.now(), Instant.now());
        when(service.createTransfer(email, new CreateTransferRequest(sourceId, beneficiaryId, new BigDecimal("10.00"))))
                .thenReturn(response);

        mvc.perform(post("/api/v1/transfers").principal(principal)
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("publicId").value(publicId.toString()))
                .andExpect(jsonPath("status").value("COMPLETED"));
    }

    private String requestJson() {
        return "{\"sourceAccountPublicId\":\"" + sourceId + "\",\"beneficiaryPublicId\":\""
                + beneficiaryId + "\",\"amount\":10.00}";
    }
}
