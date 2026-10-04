package com.fluxbank.auth.application;

import com.fluxbank.auth.api.dto.LoginRequest;
import com.fluxbank.auth.api.dto.LoginResponse;
import com.fluxbank.common.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class LoginService {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public LoginService(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request){
        String normalizedEmail = request.email().trim()
                .toLowerCase(Locale.ROOT);
        var authenticationRequest = new UsernamePasswordAuthenticationToken(
                     normalizedEmail,
                     request.password()
        );

        Authentication auth = authenticationManager.authenticate(authenticationRequest);

        UserDetails user = (UserDetails) auth.getPrincipal();
        String token = jwtService.generateToken(user);

        return new LoginResponse(
                token,
                "Bearer",
                jwtService.getExpirationSeconds()
        );
    }
}
