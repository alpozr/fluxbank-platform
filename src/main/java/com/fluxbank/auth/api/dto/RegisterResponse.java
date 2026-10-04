package com.fluxbank.auth.api.dto;
import java.util.UUID;


public record RegisterResponse(
        UUID publicId,
        String firstName,
        String lastName,
        String email
) {
}