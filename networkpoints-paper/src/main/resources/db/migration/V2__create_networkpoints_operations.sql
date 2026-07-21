CREATE TABLE networkpoints_operations (
    operation_id BINARY(16) NOT NULL,
    mutation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_uuid BINARY(16) NOT NULL,
    counterparty_uuid BINARY(16) NULL,
    request_amount DECIMAL(30, 2) NOT NULL,
    source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid BINARY(16) NULL,
    source_reference VARCHAR(255) NULL,
    award_game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    award_server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    account_balance_before DECIMAL(30, 2) NOT NULL,
    account_balance_after DECIMAL(30, 2) NOT NULL,
    account_revision_before BIGINT UNSIGNED NOT NULL,
    account_revision_after BIGINT UNSIGNED NOT NULL,
    counterparty_balance_before DECIMAL(30, 2) NULL,
    counterparty_balance_after DECIMAL(30, 2) NULL,
    counterparty_revision_before BIGINT UNSIGNED NULL,
    counterparty_revision_after BIGINT UNSIGNED NULL,
    delta DECIMAL(30, 2) NOT NULL,
    base_amount DECIMAL(30, 2) NOT NULL,
    multiplier DECIMAL(20, 8) NOT NULL,
    final_amount DECIMAL(30, 2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (operation_id),
    KEY idx_networkpoints_operations_account_created (account_uuid, created_at),
    CONSTRAINT fk_networkpoints_operations_account
        FOREIGN KEY (account_uuid) REFERENCES networkpoints_accounts (player_uuid),
    CONSTRAINT chk_networkpoints_operations_account_revision
        CHECK (account_revision_after = account_revision_before + 1),
    CONSTRAINT chk_networkpoints_operations_counterparty_revision
        CHECK (counterparty_revision_before IS NULL
            OR counterparty_revision_after = counterparty_revision_before + 1)
) ENGINE = InnoDB;

CREATE TABLE networkpoints_operation_boosters (
    operation_id BINARY(16) NOT NULL,
    entry_index SMALLINT UNSIGNED NOT NULL,
    activation_id BINARY(16) NOT NULL,
    booster_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activation_group VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    multiplier DECIMAL(20, 8) NOT NULL,
    PRIMARY KEY (operation_id, entry_index),
    CONSTRAINT fk_networkpoints_operation_boosters_operation
        FOREIGN KEY (operation_id) REFERENCES networkpoints_operations (operation_id)
) ENGINE = InnoDB;
