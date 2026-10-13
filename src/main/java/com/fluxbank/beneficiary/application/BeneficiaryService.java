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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BeneficiaryService {

    private static final String OWNER_IBAN_UNIQUE_CONSTRAINT =
            "uq_beneficiaries_owner_iban";

    private final BeneficiaryRepository beneficiaryRepository;
    private final UserRepository userRepository;
    private final IbanValidator ibanValidator;

    public BeneficiaryService(
            BeneficiaryRepository beneficiaryRepository,
            UserRepository userRepository,
            IbanValidator ibanValidator) {

        this.beneficiaryRepository = beneficiaryRepository;
        this.userRepository = userRepository;
        this.ibanValidator = ibanValidator;
    }

    @Transactional
    public BeneficiaryCreationResult create(
            String authenticatedEmail,
            CreateBeneficiaryRequest request) {

        User owner = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user not found"));

        String normalizedIban =
                ibanValidator.normalize(request.iban());

        if (!ibanValidator.isValid(normalizedIban)) {
            throw new InvalidIbanException();
        }

        return beneficiaryRepository
                .findByOwnerAndIban(owner, normalizedIban)
                .map(existing ->
                        handleExistingBeneficiary(existing))
                .orElseGet(() ->
                        createNewBeneficiary(
                                owner,
                                request,
                                normalizedIban
                        ));
    }

    private BeneficiaryCreationResult handleExistingBeneficiary(
            Beneficiary beneficiary) {

        if (beneficiary.isActive()) {
            throw new BeneficiaryAlreadyExistsException();
        }

        beneficiary.reactivate();

        return new BeneficiaryCreationResult(
                toResponse(beneficiary),
                false
        );
    }

    private BeneficiaryCreationResult createNewBeneficiary(
            User owner,
            CreateBeneficiaryRequest request,
            String normalizedIban) {

        Beneficiary beneficiary = Beneficiary.create(
                owner,
                request.firstName(),
                request.lastName(),
                normalizedIban,
                request.bankName()
        );

        try {
            Beneficiary saved =
                    beneficiaryRepository.saveAndFlush(beneficiary);

            return new BeneficiaryCreationResult(
                    toResponse(saved),
                    true
            );

        } catch (DataIntegrityViolationException exception) {

            if (isOwnerIbanUniqueConstraintViolation(exception)) {
                throw new BeneficiaryAlreadyExistsException();
            }

            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> getAll(
            String authenticatedEmail) {

        User owner = getAuthenticatedUser(authenticatedEmail);

        return beneficiaryRepository
                .findAllByOwnerAndActiveTrue(owner)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BeneficiaryResponse getByPublicId(
            String authenticatedEmail,
            UUID publicId) {

        User owner = getAuthenticatedUser(authenticatedEmail);

        Beneficiary beneficiary =
                beneficiaryRepository
                        .findByPublicIdAndOwnerAndActiveTrue(
                                publicId,
                                owner
                        )
                        .orElseThrow(
                                BeneficiaryNotFoundException::new
                        );

        return toResponse(beneficiary);
    }

    private boolean isOwnerIbanUniqueConstraintViolation(
            DataIntegrityViolationException exception) {

        Throwable cause = exception;

        while (cause != null) {
            String message = cause.getMessage();

            if (message != null
                    && message.contains(OWNER_IBAN_UNIQUE_CONSTRAINT)) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private BeneficiaryResponse toResponse(
            Beneficiary beneficiary) {

        return new BeneficiaryResponse(
                beneficiary.getPublicId(),
                beneficiary.getFirstName(),
                beneficiary.getLastName(),
                beneficiary.getIban(),
                beneficiary.getBankName()
        );
    }

    private User getAuthenticatedUser(String authenticatedEmail) {
        return userRepository
                .findByEmail(authenticatedEmail)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Authenticated user not found"
                        ));
    }

    @Transactional
    public void delete(
            String authenticatedEmail,
            UUID publicId) {

        User owner = getAuthenticatedUser(authenticatedEmail);

        Beneficiary beneficiary =
                beneficiaryRepository
                        .findByPublicIdAndOwnerAndActiveTrue(
                                publicId,
                                owner
                        )
                        .orElseThrow(
                                BeneficiaryNotFoundException::new
                        );

        beneficiary.deactivate();
    }

}