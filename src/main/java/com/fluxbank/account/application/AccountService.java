package com.fluxbank.account.application;


import com.fluxbank.account.api.AccountResponse;
import com.fluxbank.account.api.CreateAccountRequest;
import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.account.domain.exception.AccountAccessDeniedException;
import com.fluxbank.account.domain.exception.AccountNotFoundException;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.domain.UserRole;
import com.fluxbank.customer.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

       private final AccountRepository accountRepository;
       private final UserRepository userRepository;
       private final IbanGenerator ibanGenerator;
       private final AccountNumberGenerator accountNumberGenerator ;

       public AccountService(AccountRepository accountRepository, UserRepository userRepository, IbanGenerator ibanGenerator, AccountNumberGenerator accountNumberGenerator) {
              this.accountRepository = accountRepository;
              this.userRepository = userRepository;
              this.ibanGenerator = ibanGenerator;
              this.accountNumberGenerator = accountNumberGenerator;
       }

       @Transactional
       public AccountResponse createAccount(CreateAccountRequest request, String authenticatedEmail) {
              User authenticatedUser = userRepository
                      .findByEmail(authenticatedEmail)
                      .orElseThrow(() ->
                              new IllegalStateException("Authenticated user not found")
                      );
              if (authenticatedUser.getRole() == UserRole.CUSTOMER
                      && !authenticatedUser.getCustomerNumber()
                      .equals(request.customerNumber())) {

                     throw new AccountAccessDeniedException();
              }
              User targetUser = userRepository
                      .findByCustomerNumberForUpdate(request.customerNumber())
                      .orElseThrow(() ->
                              new IllegalArgumentException("Customer not found")
                      );
              int nextSuffix = accountRepository
                      .findMaxSuffixByUser(targetUser)
                      .map(maxSuffix -> maxSuffix + 1)
                      .orElse(1);
              Long ibanAccountIdentifier =
                      accountRepository.getNextIbanAccountIdentifier();
              String accountNumber = accountNumberGenerator.generate(
                      targetUser.getCustomerNumber(),
                      nextSuffix
              );
              String iban = ibanGenerator.generate(
                      ibanAccountIdentifier
              );
              Account savedAccount = Account.create(authenticatedUser, accountNumber, iban, ibanAccountIdentifier, nextSuffix, request.currency());
              accountRepository.save(savedAccount);
              return new AccountResponse(savedAccount.getPublicId(),
                      savedAccount.getUser().getCustomerNumber(),
                      savedAccount.getAccountNumber(),
                      savedAccount.getIban(),
                      savedAccount.getCurrency(),
                      savedAccount.getBalance(),
                      savedAccount.isClosed(),
                      savedAccount.isDormant(),
                      savedAccount.isBlocked(),
                      savedAccount.isExpired());
       }
       @Transactional
       public AccountResponse closeAccount(UUID accountPublicId, String authenticatedEmail){
              User authenticatedUser = userRepository
                      .findByEmail(authenticatedEmail)
                      .orElseThrow(() ->
                              new IllegalStateException("Authenticated user not found")
                      );
              Account account = accountRepository
                      .findByPublicId(accountPublicId)
                      .orElseThrow(AccountNotFoundException::new);

              if (authenticatedUser.getRole() == UserRole.CUSTOMER
                      && !authenticatedUser.getCustomerNumber()
                      .equals(account.getUser().getCustomerNumber())) {

                     throw new AccountAccessDeniedException();
              }
              account.close();
              return toResponse(account);
       }

       @Transactional
       public List<AccountResponse> getAllAccountsByUser(String email) {
              User authenticatedUser = userRepository
                      .findByEmail(email)
                      .orElseThrow(() ->
                              new IllegalStateException("Authenticated user not found")
                      );
              List<Account>  accounts = accountRepository.findAllByUser(authenticatedUser);
              return accounts.stream()
                      .map(this::toResponse)
                      .toList();

       }

       private AccountResponse toResponse(Account account) {
              return new AccountResponse(
                      account.getPublicId(),
                      account.getUser().getCustomerNumber(),
                      account.getAccountNumber(),
                      account.getIban(),
                      account.getCurrency(),
                      account.getBalance(),
                      account.isClosed(),
                      account.isDormant(),
                      account.isBlocked(),
                      account.isExpired()
              );
       }

       @Transactional
       public AccountResponse getAccount(
               UUID publicId,
               String authenticatedEmail) {
              User authenticatedUser = userRepository
                      .findByEmail(authenticatedEmail)
                      .orElseThrow(() ->
                              new IllegalStateException("Authenticated user not found")
                      );

              Account account = accountRepository
                      .findByPublicId(publicId)
                      .orElseThrow(AccountNotFoundException::new);

              if (authenticatedUser.getRole() == UserRole.CUSTOMER
                      && !authenticatedUser.getCustomerNumber()
                      .equals(account.getUser().getCustomerNumber())) {

                     throw new AccountAccessDeniedException();
              }

              return toResponse(account);
       }

       @Transactional
       public AccountResponse blockAccount(
               UUID publicId,
               String authenticatedEmail
       ) {
              User authenticatedUser = userRepository
                      .findByEmail(authenticatedEmail)
                      .orElseThrow(() ->
                              new IllegalStateException("Authenticated user not found")
                      );

              if (authenticatedUser.getRole() != UserRole.ADMIN) {
                     throw new AccountAccessDeniedException();
              }

              Account account = accountRepository
                      .findByPublicId(publicId)
                      .orElseThrow(AccountNotFoundException::new);

              account.block();

              return toResponse(account);
       }
       @Transactional
       public AccountResponse unblockAccount(
               UUID publicId,
               String authenticatedEmail
       ) {
              User authenticatedUser = userRepository
                      .findByEmail(authenticatedEmail)
                      .orElseThrow(() ->
                              new IllegalStateException("Authenticated user not found")
                      );

              if (authenticatedUser.getRole() != UserRole.ADMIN) {
                     throw new AccountAccessDeniedException();
              }

              Account account = accountRepository
                      .findByPublicId(publicId)
                      .orElseThrow(AccountNotFoundException::new);

              account.unblock();

              return toResponse(account);
       }
}
