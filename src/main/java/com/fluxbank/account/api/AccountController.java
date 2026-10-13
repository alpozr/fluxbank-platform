package com.fluxbank.account.api;


import com.fluxbank.account.application.AccountService;
import com.fluxbank.account.domain.Account;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            Authentication authentication
    ){
       AccountResponse response = accountService.createAccount(request,
               authentication.getName());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
    @PostMapping("/{publicId}/close")
    public ResponseEntity<AccountResponse> closeAccount(
            @PathVariable UUID publicId,
            Authentication authentication
    ) {

        AccountResponse response = accountService.closeAccount(
                publicId,
                authentication.getName()
        );

        return ResponseEntity.ok(response);
    }
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(
            Authentication authentication
    ) {
        List<AccountResponse> accounts =
                accountService.getAllAccountsByUser(authentication.getName());
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable UUID publicId,
            Authentication authentication
    ) {
        AccountResponse response = accountService.getAccount(
                publicId,
                authentication.getName()
        );
        return ResponseEntity.ok(response);
    }
    @PostMapping("/{publicId}/block")
    public ResponseEntity<AccountResponse> blockAccount(
            @PathVariable UUID publicId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                accountService.blockAccount(
                        publicId,
                        authentication.getName()
                )
        );
    }

    @PostMapping("/{publicId}/unblock")
    public ResponseEntity<AccountResponse> unblockAccount(
            @PathVariable UUID publicId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                accountService.unblockAccount(
                        publicId,
                        authentication.getName()
                )
        );
    }
}
