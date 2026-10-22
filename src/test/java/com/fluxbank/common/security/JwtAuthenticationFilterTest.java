package com.fluxbank.common.security;

import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.domain.UserStatus;
import com.fluxbank.customer.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final UserRepository users = mock(UserRepository.class);
    private final String secret = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    private final JwtService jwt = new JwtService(secret, 900000L);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwt, new CustomUserDetailsService(users));
    private final User user = User.createCustomer("Owner", "One", "owner@example.com", "stored-hash", 1000001L);
    private final org.springframework.security.core.userdetails.UserDetails tokenUser =
            org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                    .password("unused").authorities("ROLE_CUSTOMER").build();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeaderContinuesWithoutAuthentication() throws Exception {
        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(users);
    }

    @Test
    void nonBearerHeaderContinuesWithoutAuthentication() throws Exception {
        request.addHeader("Authorization", "Basic xyz");

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(users);
    }

    @Test
    void validBearerTokenAuthenticatesLoadedUserAndContinues() throws Exception {
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(tokenUser));

        filter.doFilter(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
        assertEquals(user.getEmail(), authentication.getName());
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),
                List.copyOf(authentication.getAuthorities()));
        assertEquals(user.getEmail(),
                ((org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal()).getUsername());
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-key", "malformed"})
    void invalidBearerTokenReturnsUnauthorizedWithoutAuthentication(String kind) throws Exception {
        String token = switch (kind) {
            case "expired" -> new JwtService(secret, -60000L).generateToken(tokenUser);
            case "wrong-key" -> new JwtService(Base64.getEncoder().encodeToString(
                    "abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8)), 900000L)
                    .generateToken(tokenUser);
            default -> "abc.def.xyz";
        };
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());
        verifyNoInteractions(users, chain);
    }

    @Test
    void validBearerTokenDoesNotReplaceExistingAuthentication() throws Exception {
        var existing = new UsernamePasswordAuthenticationToken(
                "already-authenticated@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(existing);
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(tokenUser));

        filter.doFilter(request, response, chain);

        assertSame(existing, SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(users);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"BLOCKED", "SUSPENDED", "CLOSED"})
    void nonActiveUserCannotAuthenticateWithPreviouslyIssuedToken(UserStatus status) throws Exception {
        String token = jwt.generateToken(tokenUser);
        ReflectionTestUtils.setField(user, "status", status);
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }
}
