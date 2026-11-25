package br.com.urutau.jigs.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;
    private static final String REMEMBER_ME_COOKIE = "URUTAU_REMEMBER_ME";

    @Bean
    @DependsOnDatabaseInitialization
    Administrators configuredAdministrators(
            RememberMeStore store,
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
        return new Administrators(store.synchronize(List.of(
                new RememberMeStore.Credentials(1, primaryName, primaryPassword),
                new RememberMeStore.Credentials(2, secondaryName, secondaryPassword)), encoder));
    }

    @Bean
    UserDetailsService administrators(Administrators configured) {
        var users = configured.accounts().stream()
                .map(account -> User.withUsername(account.username()).password(account.passwordHash()).roles("ADMIN").build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    PersistentTokenBasedRememberMeServices rememberMeServices(
            UserDetailsService administrators, Administrators configured, RememberMeStore store,
            @Value("${urutau.security.remember-me-days}") int days,
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookie) {
        if (days < 1 || days > Integer.MAX_VALUE / SECONDS_PER_DAY) {
            throw new IllegalStateException("REMEMBER_ME_DAYS deve ser um número de 1 a 24855.");
        }
        int validitySeconds = days * SECONDS_PER_DAY;
        var repository = store.tokenRepository(configured.accounts(), validitySeconds);
        var service = new PersistentTokenBasedRememberMeServices(UUID.randomUUID().toString(), administrators, repository) {
            @Override
            protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request,
                    HttpServletResponse response) {
                var lookupTokens = cookieTokens.length == 2
                        ? new String[] {cookieTokens[0], RememberMeStore.tokenDigest(cookieTokens[1])} : cookieTokens;
                try {
                    return super.processAutoLoginCookie(lookupTokens, request, response);
                } catch (CookieTheftException revoked) {
                    // Spring has already revoked the tokens for this account.
                    throw new RememberMeAuthenticationException("Token de autenticação persistente inválido.");
                }
            }
        };
        service.setAlwaysRemember(true);
        service.setTokenValiditySeconds(validitySeconds);
        service.setCookieName(REMEMBER_ME_COOKIE);
        service.setCookieCustomizer(cookie -> cookie.setAttribute("SameSite", "Lax"));
        if (secureCookie) {
            service.setUseSecureCookie(true);
        }
        return service;
    }

    @Bean
    SecurityFilterChain adminFilter(HttpSecurity http, PersistentTokenBasedRememberMeServices rememberMe) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login.html", "/login", "/csrf", "/assets/**", "/app.css", "/login.js",
                        "/icons.svg", "/error").permitAll()
                    .anyRequest().hasRole("ADMIN"))
                .formLogin(login -> login.loginPage("/login.html").loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true).failureUrl("/login.html?erro").permitAll())
                .rememberMe(remember -> remember.key(rememberMe.getKey()).rememberMeServices(rememberMe))
                .logout(logout -> logout.logoutSuccessUrl("/login.html?saiu").deleteCookies("JSESSIONID"))
                .addFilterAfter(new LoginPageRedirect(), RememberMeAuthenticationFilter.class)
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

    record Administrators(List<RememberMeStore.Account> accounts) {}

    private static final class LoginPageRedirect extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if ("GET".equals(request.getMethod())
                    && request.getRequestURI().equals(request.getContextPath() + "/login.html")
                    && authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                response.sendRedirect(request.getContextPath() + "/");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
