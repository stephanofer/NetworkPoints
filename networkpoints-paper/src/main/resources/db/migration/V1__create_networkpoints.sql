CREATE TABLE networkpoints_accounts (
    player_uuid BINARY(16) NOT NULL,
    last_known_name VARCHAR(16) NOT NULL,
    normalized_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    balance DECIMAL(30, 2) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid),
    UNIQUE KEY uk_networkpoints_accounts_normalized_name (normalized_name),
    CONSTRAINT chk_networkpoints_accounts_balance CHECK (balance >= 0),
    CONSTRAINT chk_networkpoints_accounts_revision CHECK (revision <= 9223372036854775807)
) ENGINE = InnoDB;

CREATE TABLE networkpoints_transactions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operation_id BINARY(16) NOT NULL,
    entry_index TINYINT UNSIGNED NOT NULL,
    account_uuid BINARY(16) NOT NULL,
    counterparty_uuid BINARY(16) NULL,
    transaction_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delta DECIMAL(30, 2) NOT NULL,
    base_amount DECIMAL(30, 2) NULL,
    multiplier DECIMAL(20, 8) NULL,
    balance_before DECIMAL(30, 2) NOT NULL,
    balance_after DECIMAL(30, 2) NOT NULL,
    revision_before BIGINT UNSIGNED NOT NULL,
    revision_after BIGINT UNSIGNED NOT NULL,
    actor_uuid BINARY(16) NULL,
    source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_reference VARCHAR(255) NULL,
    source_server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_networkpoints_transactions_operation_entry (operation_id, entry_index),
    KEY idx_networkpoints_transactions_account_created (account_uuid, created_at),
    KEY idx_networkpoints_transactions_operation (operation_id),
    KEY idx_networkpoints_transactions_created (created_at),
    CONSTRAINT fk_networkpoints_transactions_account
        FOREIGN KEY (account_uuid) REFERENCES networkpoints_accounts (player_uuid),
    CONSTRAINT chk_networkpoints_transactions_revision
        CHECK (revision_after = revision_before + 1)
) ENGINE = InnoDB;
