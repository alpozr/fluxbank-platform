package com.fluxbank.account.application;

import com.fluxbank.account.api.CreateAccountRequest;
import com.fluxbank.account.api.AccountResponse;
import com.fluxbank.account.domain.*;
import com.fluxbank.account.domain.exception.AccountAccessDeniedException;
import com.fluxbank.account.domain.exception.CustomerNotFoundException;
import com.fluxbank.account.domain.exception.AccountNotFoundException;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.customer.domain.*;
import com.fluxbank.customer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private UserRepository users;
    private AccountRepository accounts;
    private AccountBalanceRepository balances;
    private AccountService service;
    private User actor;
    private User owner;
    private Account account;
    private final CurrencyCode currency = CurrencyCode.values()[0];

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        accounts = mock(AccountRepository.class);
        balances = mock(AccountBalanceRepository.class);
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AccountService(accounts, users, new IbanGenerator(), new AccountNumberGenerator(), balances);
        actor = mock(User.class);
        when(actor.getRole()).thenReturn(UserRole.CUSTOMER);
        when(actor.getCustomerNumber()).thenReturn(1000001L);
        when(users.findByEmail("actor@example.com")).thenReturn(Optional.of(actor));
        owner = User.createCustomer("A", "B", "owner@example.com", "unused", 1000002L);
        account = Account.create(owner, "611-1000002-001", "BG00FLUX06110000000001", 1L, 1, currency);
        AccountBalance.create(account);
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
        var savedBalance = ArgumentCaptor.forClass(AccountBalance.class);
        verify(balances).save(savedBalance.capture());
        assertSame(saved.getValue(), savedBalance.getValue().getAccount());
        assertSame(savedBalance.getValue(), saved.getValue().getBalance());
        assertEquals(0, savedBalance.getValue().getCurrentBalance().signum());
        assertEquals(0, savedBalance.getValue().getReservedBalance().signum());
        assertEquals(1000002L, response.customerNumber());
        assertEquals("611-1000002-003", response.accountNumber());
        Account created = saved.getValue();
        assertNotNull(created.getPublicId());
        assertEquals(currency, created.getCurrency());
        assertEquals(3, created.getSuffix());
        assertEquals(created.getPublicId(), response.publicId());
        assertEquals(created.getIban(), response.iban());
        assertEquals(currency, response.currency());
        assertEquals(0, response.balance().signum());
        assertFalse(response.closed());
        assertFalse(response.dormant());
        assertFalse(response.blocked());
        assertFalse(response.expired());
        verify(accounts).getNextIbanAccountIdentifier();
    }

    @Test
    void customerCreatesFirstOwnAccountUsingAllocatedIbanIdentifier() {
        when(users.findByEmail("actor@example.com")).thenReturn(Optional.of(owner));
        when(users.findByCustomerNumberForUpdate(owner.getCustomerNumber())).thenReturn(Optional.of(owner));
        when(accounts.findMaxSuffixByUser(owner)).thenReturn(Optional.empty());
        Long allocatedIdentifier = 42L;
        when(accounts.getNextIbanAccountIdentifier()).thenReturn(allocatedIdentifier);

        var response = service.createAccount(
                new CreateAccountRequest(owner.getCustomerNumber(), CurrencyCode.USD), "actor@example.com");

        var saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(saved.capture());
        Account created = saved.getValue();
        assertSame(owner, created.getUser());
        assertEquals(1, created.getSuffix());
        assertEquals(allocatedIdentifier, created.getIbanAccountIdentifier());
        assertEquals("611-" + owner.getCustomerNumber() + "-001", response.accountNumber());
        assertEquals(new IbanGenerator().generate(allocatedIdentifier), response.iban());
        assertEquals(CurrencyCode.USD, response.currency());
        verify(balances).save(created.getBalance());
    }

    @Test
    void accountResponseUsesAvailableBalanceAndPublicIdentifiers() {
        when(users.findByEmail("actor@example.com")).thenReturn(Optional.of(owner));
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.of(account));
        account.getBalance().credit(new BigDecimal("1000"));
        account.getBalance().reserve(new BigDecimal("300"));

        var response = service.getAccount(account.getPublicId(), "actor@example.com");

        assertEquals(new AccountResponse(account.getPublicId(), owner.getCustomerNumber(),
                account.getAccountNumber(), account.getIban(), currency, new BigDecimal("700"),
                false, false, false, false), response);
    }

    @Test
    void listsAccountsSelectedForAuthenticatedUser() {
        when(users.findByEmail("actor@example.com")).thenReturn(Optional.of(owner));
        when(accounts.findAllByUser(owner)).thenReturn(List.of(account));

        var response = service.getAllAccountsByUser("actor@example.com");

        assertEquals(List.of(account.getPublicId()), response.stream().map(AccountResponse::publicId).toList());
        assertEquals(owner.getCustomerNumber(), response.get(0).customerNumber());
        verify(accounts).findAllByUser(owner);
    }

    @Test
    void returnsEmptyListWhenUserHasNoAccounts() {
        when(accounts.findAllByUser(actor)).thenReturn(List.of());

        assertTrue(service.getAllAccountsByUser("actor@example.com").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "close", "block", "unblock"})
    void missingAccountUsesExistingNotFoundBehavior(String operation) {
        when(actor.getRole()).thenReturn(UserRole.ADMIN);
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> {
            switch (operation) {
                case "get" -> service.getAccount(account.getPublicId(), "actor@example.com");
                case "close" -> service.closeAccount(account.getPublicId(), "actor@example.com");
                case "block" -> service.blockAccount(account.getPublicId(), "actor@example.com");
                case "unblock" -> service.unblockAccount(account.getPublicId(), "actor@example.com");
                default -> throw new IllegalArgumentException("Unexpected test operation");
            }
        });

        assertFalse(account.isClosed());
        assertFalse(account.isBlocked());
    }

    @Test
    void adminCanReadAndCloseAnotherCustomersAccount() {
        when(actor.getRole()).thenReturn(UserRole.ADMIN);
        when(accounts.findByPublicId(account.getPublicId())).thenReturn(Optional.of(account));

        assertEquals(account.getPublicId(), service.getAccount(account.getPublicId(), "actor@example.com").publicId());
        assertTrue(service.closeAccount(account.getPublicId(), "actor@example.com").closed());
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
