package com.fluxbank.common.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.User;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
    private final String secret = Base64.getEncoder().encodeToString(key);
    private final JwtService service = new JwtService(secret, 900000L);
    private final org.springframework.security.core.userdetails.UserDetails user =
            User.withUsername("owner@example.com").password("unused").authorities("ROLE_CUSTOMER").build();

    @Test
    void generatedTokenContainsSignedIdentityAuthoritiesAndBoundedTimestamps() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        String token = service.generateToken(user);

        Instant after = Instant.now();
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build()
                .parseSignedClaims(token).getPayload();
        assertEquals(user.getUsername(), claims.getSubject());
        assertEquals(List.of("ROLE_CUSTOMER"), claims.get("authorities", List.class));
        assertFalse(claims.getIssuedAt().toInstant().isBefore(before));
        assertFalse(claims.getIssuedAt().toInstant().isAfter(after));
        assertEquals(claims.getIssuedAt().toInstant().plusSeconds(900), claims.getExpiration().toInstant());
        assertEquals(900L, service.getExpirationSeconds());
    }

    @Test
    void validSignedTokenAllowsSubjectExtraction() {
        assertEquals(user.getUsername(), service.extractUsername(service.generateToken(user)));
    }

    @Test
    void expiredTokenIsRejectedWithoutWaiting() {
        String expired = new JwtService(secret, -60000L).generateToken(user);

        assertThrows(ExpiredJwtException.class, () -> service.extractUsername(expired));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String otherSecret = Base64.getEncoder().encodeToString(
                "abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8));
        String token = new JwtService(otherSecret, 900000L).generateToken(user);

        assertThrows(JwtException.class, () -> service.extractUsername(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc.def.xyz", "not-a-token"})
    void malformedTokenIsRejected(String token) {
        assertThrows(JwtException.class, () -> service.extractUsername(token));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingTokenIsRejected(String token) {
        assertThrows(IllegalArgumentException.class, () -> service.extractUsername(token));
    }
}
