package com.fluxbank.transfer.application;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class TransferReferenceGenerator {

    private final EntityManager entityManager;

    public TransferReferenceGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public String nextReference() {
        Number value = (Number) entityManager
                .createNativeQuery(
                        "SELECT nextval('fluxbank.transfer_reference_seq')"
                )
                .getSingleResult();

        String operatingDate = LocalDate.now()
                .format(DateTimeFormatter.BASIC_ISO_DATE);

        return "TRX-" + operatingDate + "-" + value.longValue();
    }
}