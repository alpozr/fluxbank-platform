package com.fluxbank.auth.api;

import com.fluxbank.auth.api.dto.LoginRequest;
import com.fluxbank.auth.api.dto.LoginResponse;
import com.fluxbank.auth.api.dto.RegisterRequest;
import com.fluxbank.auth.api.dto.RegisterResponse;
import com.fluxbank.auth.application.LoginService;
import com.fluxbank.auth.application.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final LoginService loginService;

    public AuthController(RegistrationService registrationService, LoginService loginService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        RegisterResponse response =
                registrationService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        LoginResponse loginResponse = loginService.login(request);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(loginResponse);
    }
    @GetMapping("/me")
    public ResponseEntity<String> me(Authentication authentication) {

        return ResponseEntity.ok(authentication.getName());
    }
}