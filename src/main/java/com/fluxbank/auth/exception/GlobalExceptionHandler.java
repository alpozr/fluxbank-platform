package com.fluxbank.auth.exception;

import com.fluxbank.account.domain.exception.*;
import com.fluxbank.beneficiary.exception.BeneficiaryAlreadyExistsException;
import com.fluxbank.beneficiary.exception.BeneficiaryNotFoundException;
import com.fluxbank.beneficiary.exception.InvalidIbanException;
import com.fluxbank.transfer.application.exception.SourceAccountNotFoundException;
import com.fluxbank.transfer.application.exception.BeneficiaryNotFluxBankAccountException;
import com.fluxbank.transfer.application.exception.SameAccountTransferException;
import com.fluxbank.transfer.application.exception.CurrencyMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomerNotFoundException.class)
    public ProblemDetail handleCustomerNotFound(CustomerNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
        problem.setTitle("Customer not found");
        return problem;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(
            EmailAlreadyExistsException exception) {

        ProblemDetail problemDetail =
                ProblemDetail.forStatus(HttpStatus.CONFLICT);

        problemDetail.setTitle("Email Already Exists");
        problemDetail.setDetail(exception.getMessage());
        problemDetail.setProperty(
                "errorCode",
                "AUTH_EMAIL_ALREADY_EXISTS"
        );

        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(
            MethodArgumentNotValidException exception) {

        Map<String, String> errors = new HashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        errors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        ProblemDetail problemDetail =
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        problemDetail.setTitle("Validation Failed");
        problemDetail.setDetail("Request validation failed.");
        problemDetail.setProperty(
                "errorCode",
                "VALIDATION_FAILED"
        );
        problemDetail.setProperty("errors", errors);

        return problemDetail;
    }
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleCredentialsException(
            BadCredentialsException exception) {

        Map<String, String> errors = new HashMap<>();

        ProblemDetail problemDetail =
                ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);

        problemDetail.setTitle("Invalid email or password!");
        problemDetail.setDetail(exception.getMessage());
        problemDetail.setProperty(
                "errorCode",
                "AUTH_INVALID_CREDENTIALS"
        );

        return problemDetail;
    }
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(
            AccountNotFoundException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );

        problem.setTitle("Account not found");

        return problem;
    }
    @ExceptionHandler(AccountAccessDeniedException.class)
    public ProblemDetail handleAccountAccessDenied(
            AccountAccessDeniedException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                exception.getMessage()
        );

        problem.setTitle("Account access denied");

        return problem;
    }

    @ExceptionHandler(AccountCannotBeClosedException.class)
    public ProblemDetail handleAccountCannotBeClosed(
            AccountCannotBeClosedException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Account cannot be closed");

        return problem;
    }

    @ExceptionHandler(InvalidIbanException.class)
    public ProblemDetail handleInvalidIban(
            InvalidIbanException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        problem.setTitle("Invalid IBAN");
        problem.setDetail(exception.getMessage());

        return problem;
    }

    @ExceptionHandler(BeneficiaryNotFoundException.class)
    public ProblemDetail handleBeneficiaryNotFound(
            BeneficiaryNotFoundException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

        problem.setTitle("Beneficiary not found");
        problem.setDetail(exception.getMessage());

        return problem;
    }

    @ExceptionHandler(BeneficiaryAlreadyExistsException.class)
    public ProblemDetail handleBeneficiaryAlreadyExists(
            BeneficiaryAlreadyExistsException exception) {

        ProblemDetail problem =
                ProblemDetail.forStatus(HttpStatus.CONFLICT);

        problem.setTitle("Beneficiary already exists");
        problem.setDetail(exception.getMessage());

        return problem;
    }

    @ExceptionHandler(SourceAccountNotFoundException.class)
    public ProblemDetail handleSourceAccountNotFound(
            SourceAccountNotFoundException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );

        problem.setTitle("Source account not found");
        problem.setProperty("errorCode", "TRANSFER_SOURCE_ACCOUNT_NOT_FOUND");

        return problem;
    }

    @ExceptionHandler(BeneficiaryNotFluxBankAccountException.class)
    public ProblemDetail handleBeneficiaryNotFluxBankAccount(
            BeneficiaryNotFluxBankAccountException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getMessage()
        );

        problem.setTitle("Invalid transfer destination");
        problem.setProperty("errorCode", "TRANSFER_DESTINATION_NOT_SUPPORTED");

        return problem;
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ProblemDetail handleSameAccountTransfer(
            SameAccountTransferException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getMessage()
        );

        problem.setTitle("Invalid transfer");
        problem.setProperty("errorCode", "TRANSFER_SAME_ACCOUNT");

        return problem;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(
            InsufficientFundsException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getMessage()
        );

        problem.setTitle("Insufficient funds");
        problem.setProperty("errorCode", "TRANSFER_INSUFFICIENT_FUNDS");

        return problem;
    }

    @ExceptionHandler(AccountNotAvailableForDebitException.class)
    public ProblemDetail handleAccountNotAvailableForDebit(
            AccountNotAvailableForDebitException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Source account unavailable");
        problem.setProperty("errorCode", "TRANSFER_SOURCE_ACCOUNT_UNAVAILABLE");

        return problem;
    }

    @ExceptionHandler(AccountNotAvailableForCreditException.class)
    public ProblemDetail handleAccountNotAvailableForCredit(
            AccountNotAvailableForCreditException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Destination account unavailable");
        problem.setProperty("errorCode", "TRANSFER_DESTINATION_ACCOUNT_UNAVAILABLE");

        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(
            CurrencyMismatchException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getMessage()
        );

        problem.setTitle("Currency mismatch");
        problem.setProperty("errorCode", "TRANSFER_CURRENCY_MISMATCH");

        return problem;
    }

}
