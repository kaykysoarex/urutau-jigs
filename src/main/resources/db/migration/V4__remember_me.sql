CREATE TABLE administrator_credential_state (
    account_slot INTEGER PRIMARY KEY,
    username VARCHAR(80),
    password_hash VARCHAR(60),
    credential_version VARCHAR(36),
    CONSTRAINT uq_administrator_credential_version UNIQUE (credential_version)
);

INSERT INTO administrator_credential_state (account_slot) VALUES (1), (2);

CREATE TABLE persistent_logins (
    series VARBINARY(64) PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    token VARCHAR(64) NOT NULL,
    last_used DATETIME(6) NOT NULL,
    credential_version VARCHAR(36) NOT NULL
);

CREATE INDEX ix_persistent_logins_credentials ON persistent_logins (credential_version, last_used);
