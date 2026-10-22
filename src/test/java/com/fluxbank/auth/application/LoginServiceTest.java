package com.fluxbank.auth.application;

import com.fluxbank.auth.api.dto.LoginRequest;
import com.fluxbank.auth.api.dto.LoginResponse;
import com.fluxbank.common.PasswordConfig;
import com.fluxbank.common.security.*;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.domain.UserStatus;
import com.fluxbank.customer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final PasswordEncoder encoder = spy(new PasswordConfig().passwordEncoder());
    private LoginService service;
    private User user;
    private final String email = "owner@example.com";
    private final String password = "correct-password";

    @BeforeEach
    void setUp() {
        user = User.createCustomer("Owner", "One", email, encoder.encode(password), 1000001L);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        var provider = new SecurityConfig(mock(JwtAuthenticationFilter.class))
                .authenticationProvider(new CustomUserDetailsService(users), encoder);
        service = new LoginService(new ProviderManager(provider), jwt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner@example.com", "  OWNER@EXAMPLE.COM  "})
    void validCredentialsProduceTokenAfterPasswordVerificationAndEmailNormalization(String inputEmail) {
        when(jwt.generateToken(any())).thenReturn("signed-token");
        when(jwt.getExpirationSeconds()).thenReturn(900L);

        LoginResponse response = service.login(new LoginRequest(inputEmail, password));

        assertEquals(new LoginResponse("signed-token", "Bearer", 900L), response);
        verify(encoder).matches(password, user.getPasswordHash());
        var details = org.mockito.ArgumentCaptor.forClass(org.springframework.security.core.userdetails.UserDetails.class);
        verify(jwt).generateToken(details.capture());
        assertEquals(email, details.getValue().getUsername());
        assertTrue(details.getValue().isEnabled());
    }

    @Test
    void wrongPasswordDoesNotGenerateToken() {
        assertThrows(BadCredentialsException.class,
                () -> service.login(new LoginRequest(email, "wrong-password")));

        verify(encoder).matches("wrong-password", user.getPasswordHash());
        verifyNoInteractions(jwt);
    }

    @Test
    void unknownEmailAndWrongPasswordHaveSameGenericCredentialFailure() {
        BadCredentialsException wrongPassword = assertThrows(BadCredentialsException.class,
                () -> service.login(new LoginRequest(email, "wrong-password")));
        BadCredentialsException unknownEmail = assertThrows(BadCredentialsException.class,
                () -> service.login(new LoginRequest("unknown@example.com", password)));

        assertEquals(wrongPassword.getMessage(), unknownEmail.getMessage());
        verifyNoInteractions(jwt);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"BLOCKED", "SUSPENDED", "CLOSED"})
    void nonActiveUserCannotLoginOrReceiveToken(UserStatus status) {
        ReflectionTestUtils.setField(user, "status", status);

        assertThrows(DisabledException.class, () -> service.login(new LoginRequest(email, password)));

        verifyNoInteractions(jwt);
    }
}
