package com.fluxbank.beneficiary.application;

import com.fluxbank.beneficiary.api.BeneficiaryResponse;
import com.fluxbank.beneficiary.api.CreateBeneficiaryRequest;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.beneficiary.exception.BeneficiaryAlreadyExistsException;
import com.fluxbank.beneficiary.exception.BeneficiaryNotFoundException;
import com.fluxbank.beneficiary.exception.InvalidIbanException;
import com.fluxbank.beneficiary.repository.BeneficiaryRepository;
import com.fluxbank.common.iban.IbanValidator;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BeneficiaryServiceTest {
    private static final String EMAIL = "owner@example.com";
    private static final String IBAN = "BG08FLUX06110000000001";

    @Mock private BeneficiaryRepository beneficiaries;
    @Mock private UserRepository users;
    @Spy private IbanValidator ibanValidator = new IbanValidator();
    @InjectMocks private BeneficiaryService service;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = User.createCustomer("Owner", "One", EMAIL, "unused", 1000001L);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
    }

    @Test
    void shouldCreateNewBeneficiaryForAuthenticatedOwnerWithNormalizedIban() {
        var request = new CreateBeneficiaryRequest("Ada", "Lovelace", "bg08 flux 0611 0000 0000 01", "FluxBank");
        when(beneficiaries.findByOwnerAndIban(owner, IBAN)).thenReturn(Optional.empty());
        when(beneficiaries.saveAndFlush(any(Beneficiary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(EMAIL, request);

        var saved = ArgumentCaptor.forClass(Beneficiary.class);
        verify(beneficiaries).saveAndFlush(saved.capture());
        verify(beneficiaries).findByOwnerAndIban(owner, IBAN);
        assertSame(owner, saved.getValue().getOwner());
        assertEquals(IBAN, saved.getValue().getIban());
        assertTrue(saved.getValue().isActive());
        assertTrue(result.created());
        assertEquals(new BeneficiaryResponse(saved.getValue().getPublicId(), "Ada", "Lovelace", IBAN, "FluxBank"), result.beneficiary());
    }

    @Test
    void shouldAllowSameIbanForDifferentOwners() {
        var otherOwner = User.createCustomer("Other", "Owner", "other@example.com", "unused", 1000002L);
        when(users.findByEmail(otherOwner.getEmail())).thenReturn(Optional.of(otherOwner));
        when(beneficiaries.findByOwnerAndIban(owner, IBAN)).thenReturn(Optional.empty());
        when(beneficiaries.findByOwnerAndIban(otherOwner, IBAN)).thenReturn(Optional.empty());
        when(beneficiaries.saveAndFlush(any(Beneficiary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var first = service.create(EMAIL, new CreateBeneficiaryRequest("Ada", "Lovelace", IBAN, "FluxBank"));
        var second = service.create(otherOwner.getEmail(), new CreateBeneficiaryRequest("Ada", "Lovelace", IBAN, "FluxBank"));

        assertTrue(first.created());
        assertTrue(second.created());
        assertNotEquals(first.beneficiary().publicId(), second.beneficiary().publicId());
        var saved = ArgumentCaptor.forClass(Beneficiary.class);
        verify(beneficiaries, times(2)).saveAndFlush(saved.capture());
        assertSame(owner, saved.getAllValues().get(0).getOwner());
        assertSame(otherOwner, saved.getAllValues().get(1).getOwner());
    }

    @Test
    void shouldRejectInvalidIbanBeforeLookupOrPersistence() {
        var request = new CreateBeneficiaryRequest("Ada", "Lovelace", "BG09FLUX06110000000001", "FluxBank");
        assertThrows(InvalidIbanException.class, () -> service.create(EMAIL, request));
        verifyNoInteractions(beneficiaries);
    }

    @Test
    void shouldRejectActiveDuplicateForSameOwner() {
        var existing = Beneficiary.create(owner, "Ada", "Lovelace", IBAN, "FluxBank");
        when(beneficiaries.findByOwnerAndIban(owner, IBAN)).thenReturn(Optional.of(existing));

        assertThrows(BeneficiaryAlreadyExistsException.class, () -> service.create(EMAIL,
                new CreateBeneficiaryRequest("Ada", "Lovelace", IBAN, "FluxBank")));

        assertTrue(existing.isActive());
        verify(beneficiaries, never()).saveAndFlush(any());
    }

    @Test
    void shouldReactivateSameInactiveEntityAndReturnCreatedFalse() {
        var existing = Beneficiary.create(owner, "Ada", "Lovelace", IBAN, "FluxBank");
        existing.deactivate();
        var originalPublicId = existing.getPublicId();
        when(beneficiaries.findByOwnerAndIban(owner, IBAN)).thenReturn(Optional.of(existing));

        var result = service.create(EMAIL, new CreateBeneficiaryRequest("Ada", "Lovelace", IBAN, "FluxBank"));

        assertTrue(existing.isActive());
        assertFalse(result.created());
        assertEquals(originalPublicId, result.beneficiary().publicId());
        verify(beneficiaries, never()).saveAndFlush(any());
        verify(beneficiaries, never()).save(any());
    }

    @Test
    void shouldTranslateNestedOwnerIbanUniqueConstraintViolation() {
        when(beneficiaries.findByOwnerAndIban(owner, IBAN)).thenReturn(Optional.empty());
        var sqlCause = new SQLException("duplicate key violates unique constraint \"uq_beneficiaries_owner_iban\"", "23505");
        var violation = new DataIntegrityViolationException("Could not execute statement", new RuntimeException("Persistence failure", sqlCause));
        when(beneficiaries.saveAndFlush(any())).thenThrow(violation);

        assertThrows(BeneficiaryAlreadyExistsException.class, () -> service.create(EMAIL,
                new CreateBeneficiaryRequest("Ada", "Lovelace", IBAN, "FluxBank")));
    }

    @Test
    void shouldRethrowUnrelatedIntegrityViolationUnchanged() {
        when(beneficiaries.findByOwnerAndIban(owner, IBAN)).thenReturn(Optional.empty());
        var violation = new DataIntegrityViolationException("Could not execute statement",
                new SQLException("violates foreign key constraint fk_beneficiaries_owner", "23503"));
        when(beneficiaries.saveAndFlush(any())).thenThrow(violation);

        assertSame(violation, assertThrows(DataIntegrityViolationException.class, () -> service.create(EMAIL,
                new CreateBeneficiaryRequest("Ada", "Lovelace", IBAN, "FluxBank"))));
    }

    @Test
    void shouldReturnAndMapActiveBeneficiariesSelectedForAuthenticatedOwner() {
        var first = Beneficiary.create(owner, "Ada", "Lovelace", IBAN, "FluxBank");
        var second = Beneficiary.create(owner, "Grace", "Hopper", "BG80BNBG96611020345678", "Other Bank");
        when(beneficiaries.findAllByOwnerAndActiveTrue(owner)).thenReturn(List.of(first, second));

        var result = service.getAll(EMAIL);

        assertEquals(List.of(
                new BeneficiaryResponse(first.getPublicId(), "Ada", "Lovelace", IBAN, "FluxBank"),
                new BeneficiaryResponse(second.getPublicId(), "Grace", "Hopper", "BG80BNBG96611020345678", "Other Bank")), result);
        verify(beneficiaries).findAllByOwnerAndActiveTrue(owner);
    }

    @Test
    void shouldReturnEmptyListWhenOwnerHasNoActiveBeneficiaries() {
        when(beneficiaries.findAllByOwnerAndActiveTrue(owner)).thenReturn(List.of());
        assertTrue(service.getAll(EMAIL).isEmpty());
    }

    @Test
    void shouldReturnActiveBeneficiaryOwnedByAuthenticatedUser() {
        var existing = Beneficiary.create(owner, "Ada", "Lovelace", IBAN, "FluxBank");
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(existing.getPublicId(), owner)).thenReturn(Optional.of(existing));

        assertEquals(new BeneficiaryResponse(existing.getPublicId(), "Ada", "Lovelace", IBAN, "FluxBank"),
                service.getByPublicId(EMAIL, existing.getPublicId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "inactive", "another-owner"})
    void shouldTreatUnavailableOrOtherOwnersBeneficiaryAsNotFound(String reason) {
        UUID publicId;
        if (reason.equals("absent")) {
            publicId = UUID.randomUUID();
        } else {
            var entityOwner = reason.equals("another-owner")
                    ? User.createCustomer("Other", "Owner", "other@example.com", "unused", 1000002L) : owner;
            var unavailable = Beneficiary.create(entityOwner, "Ada", "Lovelace", IBAN, "FluxBank");
            if (reason.equals("inactive")) unavailable.deactivate();
            publicId = unavailable.getPublicId();
        }
        // The owner-and-active repository predicate returns empty for all three cases.
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(publicId, owner)).thenReturn(Optional.empty());

        assertThrows(BeneficiaryNotFoundException.class, () -> service.getByPublicId(EMAIL, publicId));
        verify(beneficiaries).findByPublicIdAndOwnerAndActiveTrue(publicId, owner);
    }

    @Test
    void shouldSoftDeleteActiveBeneficiaryWithoutPhysicalDeletion() {
        var existing = spy(Beneficiary.create(owner, "Ada", "Lovelace", IBAN, "FluxBank"));
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(existing.getPublicId(), owner)).thenReturn(Optional.of(existing));

        service.delete(EMAIL, existing.getPublicId());

        verify(existing).deactivate();
        assertFalse(existing.isActive());
        verify(beneficiaries, never()).delete(any());
        verify(beneficiaries, never()).deleteById(any());
        verify(beneficiaries, never()).deleteAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "inactive", "another-owner"})
    void shouldRejectDeleteOfAbsentInactiveOrOtherOwnersBeneficiary(String reason) {
        var entityOwner = reason.equals("another-owner")
                ? User.createCustomer("Other", "Owner", "other@example.com", "unused", 1000002L) : owner;
        var unavailable = Beneficiary.create(entityOwner, "Ada", "Lovelace", IBAN, "FluxBank");
        if (reason.equals("inactive")) unavailable.deactivate();
        var publicId = reason.equals("absent") ? UUID.randomUUID() : unavailable.getPublicId();
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(publicId, owner)).thenReturn(Optional.empty());

        assertThrows(BeneficiaryNotFoundException.class, () -> service.delete(EMAIL, publicId));
        assertEquals(!reason.equals("inactive"), unavailable.isActive());
        verify(beneficiaries, never()).delete(any());
    }
}
