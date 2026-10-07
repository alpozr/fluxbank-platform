package com.fluxbank.auth.application;


import ch.qos.logback.core.util.StringUtil;
import com.fluxbank.auth.api.dto.RegisterRequest;
import com.fluxbank.auth.api.dto.RegisterResponse;
import com.fluxbank.auth.exception.EmailAlreadyExistsException;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    //TODO tight-coupling refactoring needed,trade-off
    private static final String EMAIL_UNIQUE_CONSTRAINT =
            "users_email_lower_unique_idx";

    public RegistrationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest registerRequest){
            String normalizedEmail = registerRequest.email()
                    .trim()
                    .toLowerCase(Locale.ROOT);

            if (userRepository.existsByEmail(normalizedEmail)) {
                throw new EmailAlreadyExistsException();
            }
            String passwordHash = passwordEncoder.encode(registerRequest.password());

            Long customerNumber =
                userRepository.getNextCustomerNumber();

            User userResponse = User.createCustomer(registerRequest.firstName()
                    , registerRequest.lastName(), normalizedEmail, passwordHash,customerNumber);
            try {
                  User user = userRepository.saveAndFlush(userResponse);
                  return new RegisterResponse(user.getPublicId(), user.getFirstName(), user.getLastName(), user.getEmail(),user.getCustomerNumber());
            }
            catch (DataIntegrityViolationException exception){
              if (isEmailUniqueViolation(exception)) {
                throw new EmailAlreadyExistsException();
              }
               throw exception;
            }

    }

    private boolean isEmailUniqueViolation(
            DataIntegrityViolationException exception) {

        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof
                    org.hibernate.exception.ConstraintViolationException
                            constraintViolationException) {

                return EMAIL_UNIQUE_CONSTRAINT.equals(
                        constraintViolationException.getConstraintName()
                );
            }

            cause = cause.getCause();
        }

        return false;
    }

}
