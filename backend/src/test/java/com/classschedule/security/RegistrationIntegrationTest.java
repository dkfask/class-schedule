package com.classschedule.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "app.auth.registration.enabled=true")
class RegistrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("class_schedule_registration")
                    .withUsername("class_schedule")
                    .withPassword("class_schedule");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.auth.email.code-secret", () -> "test-email-code-secret");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockBean RegistrationEmailSender emailSender;
    @Autowired org.springframework.boot.actuate.health.HealthContributorRegistry healthContributors;

    @Test
    void mailHealthIsRequiredWhenRegistrationIsEnabled() {
        Assertions.assertThat(healthContributors.getContributor("mail")).isNotNull();
    }

    private final AtomicReference<String> sentCode = new AtomicReference<>();

    @BeforeEach
    void resetDatabase() throws Exception {
        jdbc.update("DELETE FROM app_user_role");
        jdbc.update("DELETE FROM app_user WHERE username LIKE '%@example.com'");
        jdbc.update("DELETE FROM auth_email_verification_code");
        org.mockito.Mockito.doAnswer(invocation -> {
                    String body = "";
                    sentCode.set(invocation.getArgument(1, String.class));
                    return null;
                })
                .when(emailSender)
                .sendRegistrationCode(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    private String requestCode(String email) throws Exception {
        mockMvc.perform(
                        post("/api/auth/registration-code")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        return sentCode.get();
    }

    @Test
    void registerCreatesPlannerAccountThatCanLoginWithEmail() throws Exception {
        String email = "new-teacher@example.com";
        String code = requestCode(email);
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"NEW-TEACHER@EXAMPLE.COM\",\"password\":\"password-123\",\"displayName\":\"新老师\",\"verificationCode\":\""
                                                + code
                                                + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(email))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.displayName").value("新老师"))
                .andExpect(jsonPath("$.roles[0]").value("PLANNER"));
        Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM app_user WHERE username=?", String.class, email))
                .matches("^\\$2[aby]\\$.+")
                .doesNotContain("password-123");
        Assertions.assertThat(
                        jdbc.queryForObject(
                                "SELECT code_hash FROM auth_email_verification_code WHERE email=?", String.class, email))
                .isNotEqualTo(code);
        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"NEW-TEACHER@EXAMPLE.COM\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("PLANNER"));
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String email = "dup-user@example.com";
        String code = requestCode(email);
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\",\"password\":\"password-123\",\"verificationCode\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"password-123\",\"verificationCode\":\"000000\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
    }

    @Test
    void invalidEmailRejected() throws Exception {
        mockMvc.perform(
                        post("/api/auth/registration-code")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_INVALID"));
    }

    @Test
    void shortPasswordRejected() throws Exception {
        String code = requestCode("short-pw@example.com");
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"short-pw@example.com\",\"password\":\"short\",\"verificationCode\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_INVALID"));
    }

    @Test
    void wrongCodeCannotRegister() throws Exception {
        requestCode("wrong-code@example.com");
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"wrong-code@example.com\",\"password\":\"password-123\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @Test
    void codeRequestIsRateLimited() throws Exception {
        String email = "rate-limited@example.com";
        requestCode(email);
        mockMvc.perform(
                        post("/api/auth/registration-code")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CODE_RATE_LIMITED"));
    }

    @Test
    void repeatedWrongCodesAreLocked() throws Exception {
        String email = "attempts@example.com";
        requestCode(email);
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(
                            post("/api/auth/register")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"email\":\"" + email + "\",\"password\":\"password-123\",\"verificationCode\":\"123456\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CODE_INVALID"));
        }
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\",\"password\":\"password-123\",\"verificationCode\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_ATTEMPTS_EXCEEDED"));
    }
}
