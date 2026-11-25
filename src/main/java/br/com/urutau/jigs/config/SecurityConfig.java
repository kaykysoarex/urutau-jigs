package br.com.urutau.jigs.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    UserDetailsService administrators(
            BCryptPasswordEncoder encoder,
            @Value("${urutau.admin.primary.username}") String primaryName,
            @Value("${urutau.admin.primary.password}") String primaryPassword,
            @Value("${urutau.admin.secondary.username}") String secondaryName,
            @Value("${urutau.admin.secondary.password}") String secondaryPassword) {
        validateAccount(primaryName, primaryPassword, "ADMIN_1");
        validateAccount(secondaryName, secondaryPassword, "ADMIN_2");
        if (primaryName.equalsIgnoreCase(secondaryName)) {
            throw new IllegalStateException("Os dois administradores precisam de nomes de acesso distintos.");
        }
        return new InMemoryUserDetailsManager(List.of(
                User.withUsername(primaryName).password(encoder.encode(primaryPassword)).roles("ADMIN").build(),
                User.withUsername(secondaryName).password(encoder.encode(secondaryPassword)).roles("ADMIN").build()));
    }

    @Bean
    SecurityFilterChain adminFilter(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login.html", "/login", "/csrf", "/assets/**", "/app.css", "/login.js",
                        "/icons.svg", "/error").permitAll()
                    .anyRequest().hasRole("ADMIN"))
                .formLogin(login -> login.loginPage("/login.html").loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true).failureUrl("/login.html?erro").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login.html?saiu").deleteCookies("JSESSIONID"))
                .exceptionHandling(errors -> errors
                    .defaultAuthenticationEntryPointFor(
                        (request, response, error) -> jsonError(response, HttpStatus.UNAUTHORIZED, "Entre para continuar."),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                    .accessDeniedHandler((request, response, error) ->
                        jsonError(response, HttpStatus.FORBIDDEN, "A sessão expirou ou não permite esta operação.")))
                .headers(headers -> headers.frameOptions(frames -> frames.sameOrigin())
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                            + "object-src 'none'; frame-src 'self' blob:; base-uri 'self'; form-action 'self'; frame-ancestors 'self'")));
        return http.build();
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private static void validateAccount(String name, String password, String variablePrefix) {
        if (!name.matches("[A-Za-z0-9_.@-]{3,80}") || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Configure " + variablePrefix
                + "_USERNAME (3 a 80 caracteres) e " + variablePrefix + "_PASSWORD (12 caracteres, até 72 bytes).");
        }
    }

    private static void jsonError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
