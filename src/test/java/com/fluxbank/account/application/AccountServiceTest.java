package com.fluxbank.account.application;

import com.fluxbank.account.api.CreateAccountRequest;
import com.fluxbank.account.domain.*;
import com.fluxbank.account.domain.exception.AccountAccessDeniedException;
import com.fluxbank.account.domain.exception.CustomerNotFoundException;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.customer.domain.*;
import com.fluxbank.customer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private UserRepository users;
    private AccountRepository accounts;
    private AccountService service;
    private User actor;
    private User owner;
    private Account account;
    private final CurrencyCode currency = CurrencyCode.values()[0];

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        accounts = mock(AccountRepository.class);
        service = new AccountService(accounts, users, new IbanGenerator(), new AccountNumberGenerator());
        actor = mock(User.class);
        when(actor.getRole()).thenReturn(UserRole.CUSTOMER);
        when(actor.getCustomerNumber()).thenReturn(1000001L);
        when(users.findByEmail("actor@example.com")).thenReturn(Optional.of(actor));
        owner = User.createCustomer("A", "B", "owner@example.com", "unused", 1000002L);
        account = Account.create(owner, "611-1000002-001", "BG00FLUX06110000000001", 1L, 1, currency);
    }

    @Test
    void adminCreatesAccountForTargetCustomer() {
        when(actor.getRole()).thenReturn(UserRole.ADMIN);
        when(users.findByCustomerNumberForUpdate(1000002L)).thenReturn(Optional.of(owner));
        when(accounts.findMaxSuffixByUser(owner)).thenReturn(Optional.of(2));
        when(accounts.getNextIbanAccountIdentifier()).thenReturn(1L);
        var response = service.createAccount(new CreateAccountRequest(1000002L, currency), "actor@example.com");
        var saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(saved.capture());
        assertSame(owner, saved.getValue().getUser());
        assertEquals(1000002L, response.customerNumber());
        assertEquals("611-1000002-003", response.accountNumber());
    }

    @Test
    void customerCannotCreateForAnotherCustomer() {
        assertThrows(AccountAccessDeniedException.class, () -> service.createAccount(new CreateAccountRequest(1000002L, currency), "actor@example.com"));
        verifyNoInteractions(accounts);
        verify(users, never()).findByCustomerNumberForUpdate(anyLong());
    }

    @Test
    void missingCustomerHasMeaningfulException() {
        when(actor.getRole()).thenReturn(UserRole.ADMIN);
        when(users.findByCustomerNumberForUpdate(9999999L)).thenReturn(Optional.empty());
        assertThrows(CustomerNotFoundException.class, () -> service.createAccount(new CreateAccountRequest(9999999L, currency), "actor@example.com"));
        verifyNoInteractions(accounts);
    }

    @Test
    void customerCannotBlock() {
        assertThrows(AccountAccessDeniedException.class, () -> service.blockAccount(account.getPublicId(), "actor@example.com"));
        verifyNoInteractions(accounts);
    }

    @Test
    void customerCannotUnblock() {
        assertThrows(AccountAccessDeniedException.class, () -> service.unblockAccount(account.getPublicId(), "actor@example.com"));
        verifyNoInteractions(accounts);
    }

    @Test
    void adminCanBlockAndUnblock() {
        when(actor.getRole()).thenReturn(UserRole.ADMIN);
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.of(account));
        assertTrue(service.blockAccount(account.getPublicId(), "actor@example.com").blocked());
        assertFalse(service.unblockAccount(account.getPublicId(), "actor@example.com").blocked());
    }

    @Test
    void customerCannotReadAnotherCustomersAccount() {
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.of(account));
        assertThrows(AccountAccessDeniedException.class, () -> service.getAccount(account.getPublicId(), "actor@example.com"));
    }

    @Test
    void customerCannotCloseAnotherCustomersAccount() {
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.of(account));
        assertThrows(AccountAccessDeniedException.class, () -> service.closeAccount(account.getPublicId(), "actor@example.com"));
        assertFalse(account.isClosed());
    }

    @Test
    void customerCanReadAndCloseOwnAccount() {
        when(actor.getCustomerNumber()).thenReturn(owner.getCustomerNumber());
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.of(account));
        assertEquals(account.getPublicId(), service.getAccount(account.getPublicId(), "actor@example.com").publicId());
        assertTrue(service.closeAccount(account.getPublicId(), "actor@example.com").closed());
    }
}
