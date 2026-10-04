package com.fluxbank.common.security;

import com.fluxbank.customer.domain.UserStatus;
import com.fluxbank.customer.repository.UserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

        var user = userRepository.findByEmail(email).orElseThrow(()->
                new UsernameNotFoundException("Invalid credentials"));

        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities("ROLE_"+ user.getRole().name())
                .disabled(user.getStatus() != UserStatus.ACTIVE)
                .build();
    }
}
