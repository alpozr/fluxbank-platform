package com.fluxbank.account.api;

import com.fluxbank.account.application.*;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.account.domain.exception.*;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.auth.exception.GlobalExceptionHandler;
import com.fluxbank.customer.domain.*;
import com.fluxbank.customer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountControllerTest {
    private AccountService service;
    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();
    private final String email = "actor@example.com";
    private final UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(email, "unused", List.of());
    private final CurrencyCode currency = CurrencyCode.values()[0];
    private AccountResponse response;

    @BeforeEach
    void setUp() {
        service = mock(AccountService.class);
        mvc = mvcFor(service);
        response = new AccountResponse(id, 1000002L, "611-1000002-001", "BG00FLUX06110000000001", currency, BigDecimal.ZERO, false, false, false, false);
    }

    private MockMvc mvcFor(AccountService accountService) {
        return MockMvcBuilders.standaloneSetup(new AccountController(accountService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String creationJson() {
        return "{\"customerNumber\":1000002,\"currency\":\"" + currency + "\"}";
    }

    @Test
    void createReturns201AndDto() throws Exception {
        when(service.createAccount(any(), eq(email))).thenReturn(response);
        mvc.perform(post("/api/v1/accounts").principal(principal).contentType(MediaType.APPLICATION_JSON).content(creationJson()))
                .andExpect(status().isCreated()).andExpect(jsonPath("customerNumber").value(1000002))
                .andExpect(jsonPath("user").doesNotExist()).andExpect(jsonPath("id").doesNotExist());
    }

    @Test
    void listUsesBasePathAndReturns200() throws Exception {
        when(service.getAllAccountsByUser(email)).thenReturn(List.of(response));
        mvc.perform(get("/api/v1/accounts").principal(principal)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value(id.toString()));
        verify(service).getAllAccountsByUser(email);
    }

    @Test
    void detailReturns200() throws Exception {
        when(service.getAccount(id, email)).thenReturn(response);
        mvc.perform(get("/api/v1/accounts/{id}", id).principal(principal)).andExpect(status().isOk());
    }

    @Test
    void closeReturns200() throws Exception {
        when(service.closeAccount(id, email)).thenReturn(response);
        mvc.perform(post("/api/v1/accounts/{id}/close", id).principal(principal)).andExpect(status().isOk());
    }

    @Test
    void blockReturns200() throws Exception {
        when(service.blockAccount(id, email)).thenReturn(response);
        mvc.perform(post("/api/v1/accounts/{id}/block", id).principal(principal)).andExpect(status().isOk());
    }

    @Test
    void unblockReturns200() throws Exception {
        when(service.unblockAccount(id, email)).thenReturn(response);
        mvc.perform(post("/api/v1/accounts/{id}/unblock", id).principal(principal)).andExpect(status().isOk());
    }

    @Test
    void missingCustomerReturns404ProblemDetailThroughRealService() throws Exception {
        var users = mock(UserRepository.class);
        var accounts = mock(AccountRepository.class);
        var admin = mock(User.class);
        when(admin.getRole()).thenReturn(UserRole.ADMIN);
        when(users.findByEmail(email)).thenReturn(Optional.of(admin));
        when(users.findByCustomerNumberForUpdate(1000002L)).thenReturn(Optional.empty());
        var realService = new AccountService(accounts, users, new IbanGenerator(), new AccountNumberGenerator(), mock(AccountBalanceRepository.class));
        mvcFor(realService).perform(post("/api/v1/accounts").principal(principal).contentType(MediaType.APPLICATION_JSON).content(creationJson()))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("status").value(404)).andExpect(jsonPath("title").value("Customer not found"));
        verifyNoInteractions(accounts);
    }

    @Test
    void accessDeniedRemains403ProblemDetail() throws Exception {
        when(service.blockAccount(id, email)).thenThrow(new AccountAccessDeniedException());
        mvc.perform(post("/api/v1/accounts/{id}/block", id).principal(principal)).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("status").value(403));
    }

    @Test
    void accountNotFoundRemains404ProblemDetail() throws Exception {
        when(service.getAccount(id, email)).thenThrow(new AccountNotFoundException());
        mvc.perform(get("/api/v1/accounts/{id}", id).principal(principal)).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("status").value(404));
    }

    @Test
    void closeConflictRemains409ProblemDetail() throws Exception {
        when(service.closeAccount(id, email)).thenThrow(new AccountCannotBeClosedException("Account with a non-zero balance cannot be closed"));
        mvc.perform(post("/api/v1/accounts/{id}/close", id).principal(principal)).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("status").value(409));
    }
}
