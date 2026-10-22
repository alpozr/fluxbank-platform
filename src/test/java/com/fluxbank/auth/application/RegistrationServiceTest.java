package com.fluxbank.auth.application;

import com.fluxbank.auth.api.dto.RegisterRequest;
import com.fluxbank.auth.api.dto.RegisterResponse;
import com.fluxbank.auth.exception.EmailAlreadyExistsException;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.domain.UserRole;
import com.fluxbank.customer.domain.UserStatus;
import com.fluxbank.customer.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RegistrationService service = new RegistrationService(users, encoder);
    private final String email = "owner@example.com";
    private final String password = "raw-password";
    private final Long sequenceValue = 7654321L;

    private RegisterRequest request(String inputEmail) {
        return new RegisterRequest("Owner", "One", inputEmail, password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner@example.com", "  OWNER@EXAMPLE.COM  "})
    void registrationStoresNormalizedEmailEncodedPasswordAndSequenceCustomerNumber(String inputEmail) {
        when(encoder.encode(password)).thenReturn("encoded-password");
        when(users.getNextCustomerNumber()).thenReturn(sequenceValue);
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterResponse response = service.register(request(inputEmail));

        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertNotNull(user.getPublicId());
        assertEquals("Owner", user.getFirstName());
        assertEquals("One", user.getLastName());
        assertEquals(email, user.getEmail());
        assertEquals("encoded-password", user.getPasswordHash());
        assertEquals(sequenceValue, user.getCustomerNumber());
        assertEquals(UserRole.CUSTOMER, user.getRole());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(new RegisterResponse(user.getPublicId(), "Owner", "One", email, sequenceValue), response);
        verify(users).existsByEmail(email);
        verify(users).getNextCustomerNumber();
        verify(encoder).encode(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner@example.com", "  OWNER@EXAMPLE.COM  "})
    void existingNormalizedEmailIsRejectedBeforeNumberAllocationOrPersistence(String inputEmail) {
        when(users.existsByEmail(email)).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> service.register(request(inputEmail)));

        verify(users).existsByEmail(email);
        verify(users, never()).getNextCustomerNumber();
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void emailConstraintViolationIsTranslatedToExistingDomainException() {
        when(encoder.encode(password)).thenReturn("encoded-password");
        when(users.getNextCustomerNumber()).thenReturn(sequenceValue);
        var failure = integrityViolation("users_email_lower_unique_idx");
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThrows(EmailAlreadyExistsException.class, () -> service.register(request(email)));
    }

    @Test
    void unrelatedIntegrityViolationIsPropagatedUnchanged() {
        when(encoder.encode(password)).thenReturn("encoded-password");
        when(users.getNextCustomerNumber()).thenReturn(sequenceValue);
        var failure = integrityViolation("users_customer_number_unique");
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.register(request(email))));
    }

    private DataIntegrityViolationException integrityViolation(String constraint) {
        var cause = new ConstraintViolationException(
                "Constraint violation", new SQLException("duplicate key", "23505"), constraint);
        return new DataIntegrityViolationException("Persistence failure", new RuntimeException(cause));
    }
}
