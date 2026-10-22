package com.fluxbank.auth.api;

import com.fluxbank.auth.api.dto.RegisterResponse;
import com.fluxbank.auth.application.LoginService;
import com.fluxbank.auth.application.RegistrationService;
import com.fluxbank.auth.exception.GlobalExceptionHandler;
import com.fluxbank.common.PasswordConfig;
import com.fluxbank.common.security.*;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.domain.UserStatus;
import com.fluxbank.customer.repository.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private UserRepository users;
    private RegistrationService registration;
    private JwtService jwt;
    private User user;
    private final String password = "correct-password";

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, PasswordConfig.class, AuthController.class, GlobalExceptionHandler.class,
            LoginService.class, CustomUserDetailsService.class, JwtAuthenticationFilter.class})
    static class Config {
        @Bean
        UserRepository users() {
            return mock(UserRepository.class);
        }

        @Bean
        RegistrationService registration() {
            return mock(RegistrationService.class);
        }

        @Bean
        JwtService jwtService() {
            return spy(new JwtService(Base64.getEncoder().encodeToString(
                    "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)), 900000L));
        }
    }

    @BeforeAll
    void startSecurityContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        users = context.getBean(UserRepository.class);
        registration = context.getBean(RegistrationService.class);
        jwt = context.getBean(JwtService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        reset(users, registration);
        clearInvocations(jwt);
        user = User.createCustomer("Owner", "One", "owner@example.com",
                context.getBean(PasswordEncoder.class).encode(password), 1000001L);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void loginEndpointIsPublicAndReturnsCurrentTokenContract() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(user.getEmail(), password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("accessToken").isNotEmpty())
                .andExpect(jsonPath("tokenType").value("Bearer"))
                .andExpect(jsonPath("expiresIn").value(900));
    }

    @Test
    void registrationEndpointIsPublic() throws Exception {
        when(registration.register(any())).thenReturn(new RegisterResponse(
                UUID.randomUUID(), "New", "User", "new@example.com", 1000002L));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"New","lastName":"User","email":"new@example.com","password":"valid-password"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("email").value("new@example.com"));
    }

    @Test
    void unknownEmailAndWrongPasswordHaveIdenticalPublicCredentialErrors() throws Exception {
        var wrongPassword = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(user.getEmail(), "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("errorCode").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse();
        var unknownEmail = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("unknown@example.com", password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("errorCode").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse();

        assertEquals(wrongPassword.getContentAsString(), unknownEmail.getContentAsString());
        verify(jwt, never()).generateToken(any());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsAuthenticatedEmailForValidJwt() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(content().string(user.getEmail()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-key", "malformed"})
    void invalidJwtCannotAccessProtectedMe(String kind) throws Exception {
        var details = new CustomUserDetailsService(users).loadUserByUsername(user.getEmail());
        String token = switch (kind) {
            case "expired" -> new JwtService(Base64.getEncoder().encodeToString(
                    "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)), -60000L)
                    .generateToken(details);
            case "wrong-key" -> new JwtService(Base64.getEncoder().encodeToString(
                    "abcdefghijklmnopqrstuvwxyz123456".getBytes(StandardCharsets.UTF_8)), 900000L)
                    .generateToken(details);
            default -> "abc.def.xyz";
        };

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"BLOCKED", "SUSPENDED", "CLOSED"})
    void nonActiveUserCannotAccessProtectedMeWithPreviouslyIssuedToken(UserStatus status) throws Exception {
        String token = token();
        ReflectionTestUtils.setField(user, "status", status);

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String token() {
        return jwt.generateToken(new CustomUserDetailsService(users).loadUserByUsername(user.getEmail()));
    }

    private String loginJson(String email, String rawPassword) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + rawPassword + "\"}";
    }
}
