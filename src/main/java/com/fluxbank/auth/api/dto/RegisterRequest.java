package com.fluxbank.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest (
     @NotBlank
     @Size(max=100)
     String firstName,

     @NotBlank
     @Size(max=100)
     String lastName,

     @Size(max=255)
     @NotBlank
     @Email
     String email,

     @NotBlank
     @Size(min=8 , max=72)
     String password
)
{
}
