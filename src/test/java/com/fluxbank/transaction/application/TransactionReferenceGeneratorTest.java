package com.fluxbank.transaction.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TransactionReferenceGeneratorTest {
    @Test
    void formatsOperatingDateAndUsesDatabaseSequenceOnEveryCall() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery("SELECT nextval('fluxbank.transaction_reference_seq')"))
                .thenReturn(query);
        when(query.getSingleResult()).thenReturn(100001L, 100002L);
        TransactionReferenceGenerator generator = new TransactionReferenceGenerator(entityManager);

        try (MockedStatic<LocalDate> dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            LocalDate date = LocalDate.of(2026, 10, 21);
            dates.when(LocalDate::now).thenReturn(date);
            assertEquals("TXN-20261021-100001", generator.nextReference());
            assertEquals("TXN-20261021-100002", generator.nextReference());
        }

        verify(entityManager, times(2)).createNativeQuery("SELECT nextval('fluxbank.transaction_reference_seq')");
        verify(query, times(2)).getSingleResult();
    }
}
