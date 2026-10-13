package com.fluxbank.auth.exception;

import com.fluxbank.account.domain.exception.AccountAccessDeniedException;
import com.fluxbank.account.domain.exception.AccountCannotBeClosedException;
import com.fluxbank.account.domain.exception.AccountNotFoundException;
import com.fluxbank.account.domain.exception.CustomerNotFoundException;
import com.fluxbank.beneficiary.exception.BeneficiaryAlreadyExistsException;
import com.fluxbank.beneficiary.exception.BeneficiaryNotFoundException;
import com.fluxbank.beneficiary.exception.InvalidIbanException;
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
}
