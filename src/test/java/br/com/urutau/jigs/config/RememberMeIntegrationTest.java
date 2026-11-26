package br.com.urutau.jigs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.urutau.jigs.UrutauApplication;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:remember_me_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "server.servlet.session.cookie.secure=true",
    "REMEMBER_ME_DAYS=90",
    "urutau.admin.primary.username=" + RememberMeIntegrationTest.PRIMARY,
    "urutau.admin.primary.password=" + RememberMeIntegrationTest.PRIMARY_PASSWORD,
    "urutau.admin.secondary.username=" + RememberMeIntegrationTest.SECONDARY,
    "urutau.admin.secondary.password=" + RememberMeIntegrationTest.SECONDARY_PASSWORD
})
@ActiveProfiles("remember-me-test")
@AutoConfigureMockMvc
class RememberMeIntegrationTest {
    static final String PRIMARY = "remember-primary";
    static final String PRIMARY_PASSWORD = "remember-primary-test-password";
    static final String SECONDARY = "remember-secondary";
    static final String SECONDARY_PASSWORD = "remember-secondary-test-password";
    private static final String COOKIE_NAME = "URUTAU_REMEMBER_ME";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @Test
    void issuesSecurePersistentCookieWithoutAnExplicitOptInAndRestoresASession() throws Exception {
        var issued = signedIn(mvc, PRIMARY, PRIMARY_PASSWORD);
        assertCookiePolicy(issued, 90, true);
        assertTokenStoredAsDigest(issued);
        var restored = restore(mvc, issued, PRIMARY);
        var rotated = rememberCookie(restored);
        assertCookiePolicy(rotated, 90, true);
        assertThat(rotated.getValue().equals(issued.getValue())).as("the persistent token was rotated").isFalse();
        assertTokenStoredAsDigest(rotated);

        var session = (MockHttpSession) restored.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/api/session").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(PRIMARY));
    }

    @Test
    void keepsTheLoginPageAvailableForAnonymousHealthchecksAndRedirectsRememberedUsers() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        var cookie = signedIn(mvc, PRIMARY, PRIMARY_PASSWORD);

        var redirect = mvc.perform(get("/login.html").cookie(cookie))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn();
        var activeSession = (MockHttpSession) redirect.getRequest().getSession(false);
        assertThat(activeSession).isNotNull();
        mvc.perform(get("/login.html").session(activeSession))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
    }

    @Test
    void publishesOnlyThePwaAssetsWhileProtectingAdministrativePagesAndData() throws Exception {
        mvc.perform(get("/manifest.webmanifest"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/manifest+json"));
        mvc.perform(get("/service-worker.js"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/javascript"));
        mvc.perform(get("/offline.html")).andExpect(status().isOk());
        mvc.perform(get("/pwa.js").param("connection-check", "1")).andExpect(status().isOk());
        mvc.perform(head("/icons/favicon.ico")).andExpect(status().isOk());
        for (var icon : new String[] {"icon-192.png", "icon-512.png", "icon-maskable-512.png", "apple-touch-icon.png"}) {
            mvc.perform(get("/icons/" + icon))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        }
        for (var path : new String[] {"/", "/index.html", "/app.js", "/icons/private.png"}) {
            mvc.perform(get(path).accept("text/html"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html"));
        }
        mvc.perform(get("/api/quotes/1/pdf")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRequiresCsrfInvalidatesTheSessionAndRevokesOnlyThatAccountsTokens() throws Exception {
        var signedInOnAnotherDevice = signedIn(mvc, PRIMARY, PRIMARY_PASSWORD);
        var secondaryCookie = signedIn(mvc, SECONDARY, SECONDARY_PASSWORD);
        var restored = restore(mvc, signedIn(mvc, PRIMARY, PRIMARY_PASSWORD), PRIMARY);
        var cookie = rememberCookie(restored);
        var session = (MockHttpSession) restored.getRequest().getSession(false);
        assertThat(session).isNotNull();
        var csrf = json.readTree(restored.getResponse().getContentAsString());

        mvc.perform(post("/logout").session(session).cookie(cookie)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();

        var logout = mvc.perform(post("/logout").session(session).cookie(cookie)
                .param("_csrf", csrf.path("csrfToken").asString()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html?saiu")).andReturn();
        assertThat(session.isInvalid()).isTrue();
        assertThat(rememberCookie(logout).getMaxAge()).isZero();
        rejectsCookie(mvc, cookie);
        rejectsCookie(mvc, signedInOnAnotherDevice);
        restore(mvc, secondaryCookie, SECONDARY);
    }

    @Test
    void rejectsExpiredTokensAndClearsTheirCookies() throws Exception {
        var cookie = signedIn(mvc, PRIMARY, PRIMARY_PASSWORD);
        jdbc.update("UPDATE persistent_logins SET last_used = ? WHERE username = ?",
            Timestamp.from(Instant.now().minus(Duration.ofDays(91))), PRIMARY);

        rejectsCookie(mvc, cookie);
    }

    @Test
    void rejectsReplayAfterRotationAndRevokesTheCompromisedSeries() throws Exception {
        var original = signedIn(mvc, PRIMARY, PRIMARY_PASSWORD);
        var rotated = rememberCookie(restore(mvc, original, PRIMARY));

        rejectsCookie(mvc, original);
        rejectsCookie(mvc, rotated);
    }

    @Test
    void remembersBothAccountsAcrossRestartsAndRevokesTokensAfterChanges(@TempDir Path data) throws Exception {
        var database = "jdbc:h2:file:" + data.resolve("remember-me-restart").toString().replace('\\', '/')
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        Cookie primaryCookie;
        Cookie secondaryCookie;
        try (var first = instance(database, PRIMARY_PASSWORD, SECONDARY)) {
            primaryCookie = signedIn(mockMvc(first), PRIMARY, PRIMARY_PASSWORD);
            secondaryCookie = signedIn(mockMvc(first), SECONDARY, SECONDARY_PASSWORD);
            assertCookiePolicy(primaryCookie, 37, false);
        }

        try (var restarted = instance(database, PRIMARY_PASSWORD, SECONDARY)) {
            var browser = mockMvc(restarted);
            primaryCookie = rememberCookie(restore(browser, primaryCookie, PRIMARY));
            secondaryCookie = rememberCookie(restore(browser, secondaryCookie, SECONDARY));
        }

        var changedPassword = "remember-primary-changed-password";
        var renamedSecondary = "remember-secondary-renamed";
        try (var restarted = instance(database, changedPassword, renamedSecondary)) {
            var browser = mockMvc(restarted);
            rejectsCookie(browser, primaryCookie);
            rejectsCookie(browser, secondaryCookie);
            signedIn(browser, PRIMARY, changedPassword);
            signedIn(browser, renamedSecondary, SECONDARY_PASSWORD);
        }
    }

    private static ConfigurableApplicationContext instance(String database, String primaryPassword, String secondaryName) {
        return new SpringApplicationBuilder(UrutauApplication.class)
            .web(WebApplicationType.SERVLET)
            .contextFactory(type -> new GenericWebApplicationContext(new MockServletContext()))
            .registerShutdownHook(false)
            .run(
                "--spring.profiles.active=remember-me-test",
                "--spring.datasource.url=" + database,
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--server.servlet.session.cookie.secure=false",
                "--urutau.security.remember-me-days=37",
                "--urutau.admin.primary.username=" + PRIMARY,
                "--urutau.admin.primary.password=" + primaryPassword,
                "--urutau.admin.secondary.username=" + secondaryName,
                "--urutau.admin.secondary.password=" + SECONDARY_PASSWORD
            );
    }

    private static MockMvc mockMvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).apply(springSecurity()).build();
    }

    private static Cookie signedIn(MockMvc browser, String username, String password) throws Exception {
        return rememberCookie(browser.perform(formLogin("/login").user(username).password(password))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn());
    }

    private static MvcResult restore(MockMvc browser, Cookie cookie, String username) throws Exception {
        return browser.perform(get("/api/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.csrfToken").isNotEmpty()).andReturn();
    }

    private static void rejectsCookie(MockMvc browser, Cookie cookie) throws Exception {
        var rejected = browser.perform(get("/api/session").cookie(cookie))
            .andExpect(status().isUnauthorized()).andReturn();
        assertThat(rememberCookie(rejected).getMaxAge()).isZero();
    }

    private static Cookie rememberCookie(MvcResult result) {
        var cookie = result.getResponse().getCookie(COOKIE_NAME);
        assertThat(cookie != null).as("response sets the remember-me cookie").isTrue();
        return cookie;
    }

    private static void assertCookiePolicy(Cookie cookie, int days, boolean secure) {
        assertThat(cookie.getMaxAge()).isEqualTo(Math.toIntExact(Duration.ofDays(days).toSeconds()));
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isEqualTo(secure);
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    private void assertTokenStoredAsDigest(Cookie cookie) throws Exception {
        var encoded = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8).split(":", 2);
        assertThat(encoded.length).isEqualTo(2);
        var series = URLDecoder.decode(encoded[0], StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        var token = URLDecoder.decode(encoded[1], StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        var expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token));
        var stored = jdbc.queryForObject("SELECT token FROM persistent_logins WHERE series = ?", String.class, series);
        assertThat(expected.equals(stored)).as("the database stores only the token digest").isTrue();
    }
}
