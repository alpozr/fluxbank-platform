package com.fluxbank.transaction.repository;

import com.fluxbank.account.domain.Account;
import com.fluxbank.transaction.domain.Transaction;
import com.fluxbank.transaction.domain.TransactionDirection;
import com.fluxbank.transaction.domain.TransactionType;
import com.fluxbank.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    boolean existsByTransferAndAccountAndTypeAndDirection(
            Transfer transfer,
            Account account,
            TransactionType type,
            TransactionDirection direction
    );
}
