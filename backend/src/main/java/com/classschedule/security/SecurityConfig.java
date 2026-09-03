package com.classschedule.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableMethodSecurity
@org.springframework.context.annotation.Profile("!worker")
public class SecurityConfig {
    @Bean
    DaoAuthenticationProvider authenticationProvider(
            AppUserRepository users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http.csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrfHandler))
                .sessionManagement(
                        config -> config.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/health",
                                                "/actuator/health",
                                                "/api/auth/login",
                                                "/api/auth/csrf")
                                        .permitAll()
                                        .requestMatchers("/api/auth/**")
                                        .authenticated()
                                        .requestMatchers("/api/schedule-versions/*/publish")
                                        .hasRole("PLANNER")
                                        .requestMatchers(
                                                "/api/schedule-versions/*/adjustments/**",
                                                "/api/schedule-versions/*/lock",
                                                "/api/schedule-versions/*/archive",
                                                "/api/schedule-versions/*/fork")
                                        .hasRole("PLANNER")
                                        .requestMatchers(
                                                "/api/master-data/**",
                                                "/api/rule-facts/**",
                                                "/api/schedule-rules/**",
                                                "/api/imports/**",
                                                "/api/solve-jobs/**",
                                                "/api/legacy-solve-jobs/**")
                                        .hasRole("PLANNER")
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(config -> config.disable())
                .formLogin(config -> config.disable())
                .logout(config -> config.disable())
                .exceptionHandling(
                        config ->
                                config.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(401))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(403)));
        return http.build();
    }
}
