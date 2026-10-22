package com.fluxbank.transaction.application;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class TransactionReferenceGenerator {

    private final EntityManager entityManager;

    public TransactionReferenceGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public String nextReference() {
        Number value = (Number) entityManager
                .createNativeQuery(
                        "SELECT nextval('fluxbank.transaction_reference_seq')"
                )
                .getSingleResult();

        String operatingDate = LocalDate.now()
                .format(DateTimeFormatter.BASIC_ISO_DATE);

        return "TXN-" + operatingDate + "-" + value.longValue();
    }
}
