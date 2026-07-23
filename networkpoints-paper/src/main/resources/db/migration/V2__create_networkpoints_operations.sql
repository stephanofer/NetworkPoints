CREATE TABLE `${tablePrefix}operations` (
    operation_id BINARY(16) NOT NULL,
    mutation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_uuid BINARY(16) NOT NULL,
    counterparty_uuid BINARY(16) NULL,
    request_amount DECIMAL(30, 2) NOT NULL,
    source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_uuid BINARY(16) NULL,
    source_reference VARCHAR(255) NULL,
    award_game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    award_server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    account_balance_before DECIMAL(30, 2) NULL,
    account_balance_after DECIMAL(30, 2) NULL,
    account_revision_before BIGINT UNSIGNED NULL,
    account_revision_after BIGINT UNSIGNED NULL,
    counterparty_balance_before DECIMAL(30, 2) NULL,
    counterparty_balance_after DECIMAL(30, 2) NULL,
    counterparty_revision_before BIGINT UNSIGNED NULL,
    counterparty_revision_after BIGINT UNSIGNED NULL,
    delta DECIMAL(30, 2) NULL,
    base_amount DECIMAL(30, 2) NULL,
    multiplier DECIMAL(20, 8) NULL,
    final_amount DECIMAL(30, 2) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (operation_id),
    KEY `idx_${tablePrefix}operations_account_created` (account_uuid, created_at),
    CONSTRAINT `chk_${tablePrefix}operations_mutation_type`
        CHECK (mutation_type IN ('AWARD', 'CREDIT', 'DEBIT', 'SET_BALANCE', 'TRANSFER')),
    CONSTRAINT `chk_${tablePrefix}operations_type`
        CHECK ((mutation_type = 'TRANSFER') = (counterparty_uuid IS NOT NULL)),
    CONSTRAINT `chk_${tablePrefix}operations_award_identity`
        CHECK ((mutation_type = 'AWARD' AND award_game_id IS NOT NULL AND award_server_id IS NOT NULL)
            OR (mutation_type <> 'AWARD' AND award_game_id IS NULL AND award_server_id IS NULL)),
    CONSTRAINT `chk_${tablePrefix}operations_status`
        CHECK (outcome_status IN ('SUCCESS', 'INSUFFICIENT_FUNDS', 'BALANCE_LIMIT_EXCEEDED', 'ACCOUNT_NOT_FOUND')),
    CONSTRAINT `chk_${tablePrefix}operations_account_before`
        CHECK ((account_balance_before IS NULL) = (account_revision_before IS NULL)),
    CONSTRAINT `chk_${tablePrefix}operations_success`
        CHECK (outcome_status <> 'SUCCESS' OR (
            account_balance_before IS NOT NULL
            AND account_balance_after IS NOT NULL
            AND account_revision_after = account_revision_before + 1
            AND delta IS NOT NULL
            AND base_amount IS NOT NULL
            AND multiplier IS NOT NULL
            AND final_amount IS NOT NULL
            AND ((mutation_type = 'TRANSFER'
                AND counterparty_balance_before IS NOT NULL
                AND counterparty_balance_after IS NOT NULL
                AND counterparty_revision_before IS NOT NULL
                AND counterparty_revision_after = counterparty_revision_before + 1)
                OR (mutation_type <> 'TRANSFER'
                    AND counterparty_balance_before IS NULL
                    AND counterparty_balance_after IS NULL
                    AND counterparty_revision_before IS NULL
                    AND counterparty_revision_after IS NULL)))),
    CONSTRAINT `chk_${tablePrefix}operations_rejection`
        CHECK (outcome_status = 'SUCCESS' OR (
            account_balance_after IS NULL
            AND account_revision_after IS NULL
            AND counterparty_balance_before IS NULL
            AND counterparty_balance_after IS NULL
            AND counterparty_revision_before IS NULL
            AND counterparty_revision_after IS NULL
            AND delta IS NULL
            AND base_amount IS NULL
            AND multiplier IS NULL
            AND final_amount IS NULL))
) ENGINE = InnoDB;

CREATE TABLE `${tablePrefix}operation_boosters` (
    operation_id BINARY(16) NOT NULL,
    entry_index SMALLINT UNSIGNED NOT NULL,
    activation_id BINARY(16) NOT NULL,
    booster_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activation_group VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    multiplier DECIMAL(20, 8) NOT NULL,
    PRIMARY KEY (operation_id, entry_index),
    CONSTRAINT `fk_${tablePrefix}operation_boosters_operation`
        FOREIGN KEY (operation_id) REFERENCES `${tablePrefix}operations` (operation_id)
) ENGINE = InnoDB;
