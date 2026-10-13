package com.fluxbank.beneficiary.repository;

import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository
        extends JpaRepository<Beneficiary, Long> {

    Optional<Beneficiary> findByOwnerAndIban(
            User owner,
            String iban
    );

    List<Beneficiary> findAllByOwnerAndActiveTrue(
            User owner
    );

    Optional<Beneficiary> findByPublicIdAndOwnerAndActiveTrue(
            UUID publicId,
            User owner
    );
}