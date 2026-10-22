package com.fluxbank.common.security;

import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.domain.UserRole;
import com.fluxbank.customer.domain.UserStatus;
import com.fluxbank.customer.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(users);
    private final User user = User.createCustomer("Owner", "One", "owner@example.com", "stored-hash", 1000001L);

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void mapsExistingUserIdentityPasswordAndRole(UserRole role) {
        ReflectionTestUtils.setField(user, "role", role);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        var details = service.loadUserByUsername(user.getEmail());

        assertEquals(user.getEmail(), details.getUsername());
        assertEquals("stored-hash", details.getPassword());
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())),
                List.copyOf(details.getAuthorities()));
        assertTrue(details.isEnabled());
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void onlyActiveUsersAreEnabled(UserStatus status) {
        ReflectionTestUtils.setField(user, "status", status);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        assertEquals(status == UserStatus.ACTIVE,
                service.loadUserByUsername(user.getEmail()).isEnabled());
    }

    @Test
    void missingUserHasGenericCredentialsError() {
        UsernameNotFoundException failure = assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown@example.com"));

        assertEquals("Invalid credentials", failure.getMessage());
    }
}
