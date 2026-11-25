package br.com.urutau.jigs.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class RememberMeStore {
    private final JdbcTemplate jdbc;

    RememberMeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<Account> synchronize(List<Credentials> credentials, BCryptPasswordEncoder encoder) {
        // Lock both administrator slots so two instances cannot rotate the same credential concurrently.
        Map<Integer, Account> previousAccounts = jdbc.query("""
                SELECT account_slot, username, password_hash, credential_version
                FROM administrator_credential_state
                ORDER BY account_slot FOR UPDATE
                """, rows -> {
            Map<Integer, Account> accounts = new HashMap<>();
            while (rows.next()) {
                accounts.put(rows.getInt("account_slot"), new Account(rows.getString("username"),
                        rows.getString("password_hash"), rows.getString("credential_version")));
            }
            return accounts;
        });

        List<Account> accounts = new ArrayList<>();
        for (Credentials configured : credentials) {
            Account previous = Objects.requireNonNull(previousAccounts.get(configured.slot()),
                    "Slot administrativo ausente. Confira as migrações do Flyway.");
            if (configured.username().equals(previous.username()) && previous.passwordHash() != null
                    && previous.generation() != null && encoder.matches(configured.password(), previous.passwordHash())) {
                accounts.add(previous);
                continue;
            }

            Account current = new Account(configured.username(), encoder.encode(configured.password()),
                    UUID.randomUUID().toString());
            jdbc.update("""
                    UPDATE administrator_credential_state
                    SET username = ?, password_hash = ?, credential_version = ?
                    WHERE account_slot = ?
                    """, current.username(), current.passwordHash(), current.generation(), configured.slot());
            if (previous.generation() != null) {
                jdbc.update("DELETE FROM persistent_logins WHERE credential_version = ?", previous.generation());
            }
            accounts.add(current);
        }
        return List.copyOf(accounts);
    }

    PersistentTokenRepository tokenRepository(List<Account> accounts, int validitySeconds) {
        Map<String, String> generations = accounts.stream()
                .collect(Collectors.toUnmodifiableMap(Account::username, Account::generation));
        return new TokenRepository(new NamedParameterJdbcTemplate(jdbc), generations, validitySeconds);
    }

    static String tokenDigest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 não está disponível.", error);
        }
    }

    record Credentials(int slot, String username, String password) {
        @Override
        public String toString() {
            return "Credentials[slot=" + slot + "]";
        }
    }

    record Account(String username, String passwordHash, String generation) {
        @Override
        public String toString() {
            return "Account[username=" + username + "]";
        }
    }

    private static class TokenRepository implements PersistentTokenRepository {
        // Accept a token only if it matches both this application's configuration and the current database state.
        private static final String ACTIVE_GENERATION = """

                AND credential_version IN (:bootGenerations)
                AND EXISTS (
                    SELECT 1 FROM administrator_credential_state
                    WHERE administrator_credential_state.credential_version = persistent_logins.credential_version
                )
                """;

        private final NamedParameterJdbcTemplate jdbc;
        private final Map<String, String> generations;
        private final Set<String> bootGenerations;
        private final int validitySeconds;

        private TokenRepository(NamedParameterJdbcTemplate jdbc, Map<String, String> generations, int validitySeconds) {
            this.jdbc = jdbc;
            this.generations = generations;
            this.bootGenerations = Set.copyOf(generations.values());
            this.validitySeconds = validitySeconds;
        }

        @Override
        public void createNewToken(PersistentRememberMeToken token) {
            String generation = generations.get(token.getUsername());
            if (generation == null) {
                throw invalidatedToken();
            }

            Timestamp cutoff = Timestamp.from(token.getDate().toInstant().minusSeconds(validitySeconds));
            jdbc.update("""
                    DELETE FROM persistent_logins
                    WHERE credential_version = :generation AND last_used < :cutoff
                    """, Map.of("generation", generation, "cutoff", cutoff));
            int inserted = jdbc.update("""
                    INSERT INTO persistent_logins (series, username, token, last_used, credential_version)
                    SELECT :series, username, :token, :lastUsed, credential_version
                    FROM administrator_credential_state
                    WHERE username = :username AND credential_version = :generation
                    """, Map.of("series", seriesKey(token.getSeries()), "token", tokenDigest(token.getTokenValue()),
                    "lastUsed", new Timestamp(token.getDate().getTime()), "username", token.getUsername(),
                    "generation", generation));
            if (inserted != 1) {
                throw invalidatedToken();
            }
        }

        @Override
        public void updateToken(String series, String tokenValue, Date lastUsed) {
            int updated = jdbc.update("""
                    UPDATE persistent_logins SET token = :token, last_used = :lastUsed
                    WHERE series = :series
                    """ + ACTIVE_GENERATION, Map.of("series", seriesKey(series), "token", tokenDigest(tokenValue),
                    "lastUsed", new Timestamp(lastUsed.getTime()), "bootGenerations", bootGenerations));
            if (updated != 1) {
                throw invalidatedToken();
            }
        }

        @Override
        public PersistentRememberMeToken getTokenForSeries(String series) {
            return jdbc.query("""
                    SELECT username, token, last_used FROM persistent_logins
                    WHERE series = :series
                    """ + ACTIVE_GENERATION, Map.of("series", seriesKey(series), "bootGenerations", bootGenerations),
                    (row, number) -> new PersistentRememberMeToken(row.getString("username"), series,
                            row.getString("token"), row.getTimestamp("last_used")))
                    .stream().findFirst().orElse(null);
        }

        @Override
        public void removeUserTokens(String username) {
            String generation = generations.get(username);
            if (generation != null) {
                jdbc.update("DELETE FROM persistent_logins WHERE username = :username AND credential_version = :generation",
                        Map.of("username", username, "generation", generation));
            }
        }

        private static byte[] seriesKey(String series) {
            return series.getBytes(StandardCharsets.UTF_8);
        }

        private static RememberMeAuthenticationException invalidatedToken() {
            return new RememberMeAuthenticationException("O login persistente não está mais disponível.");
        }
    }
}
