package com.stephanofer.networkpoints.persistence;

import com.hera.craftkit.database.Database;
import com.hera.craftkit.database.DatabaseConfig;
import com.hera.craftkit.database.Databases;
import com.hera.craftkit.database.ExecutorConfig;
import com.hera.craftkit.database.ExistingSchemaStrategy;
import com.hera.craftkit.database.MigrationConfig;
import com.hera.craftkit.database.PoolConfig;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.util.Objects;

public final class DatabaseFactory {

    private DatabaseFactory() {
    }

    public static Database create(ConfigSnapshot.Database source, ClassLoader classLoader) {
        Objects.requireNonNull(source, "source");
        PoolConfig pool = PoolConfig.builder()
                .poolName("networkpoints-mysql")
                .maximumPoolSize(source.maximumPoolSize())
                .minimumIdle(source.minimumIdle())
                .connectionTimeoutMillis(source.connectionTimeoutMillis())
                .validationTimeoutMillis(source.validationTimeoutMillis())
                .build();
        ExecutorConfig executor = ExecutorConfig.builder()
                .threadCount(source.maximumPoolSize())
                .threadNamePrefix("networkpoints-database")
                .shutdownTimeoutMillis(source.shutdownTimeoutMillis())
                .build();
        MigrationConfig migration = MigrationConfig.builder()
                .existingSchemaStrategy(ExistingSchemaStrategy.BASELINE_AT_ZERO)
                .classLoader(Objects.requireNonNull(classLoader, "classLoader"))
                .build();
        DatabaseConfig config = DatabaseConfig.builder()
                .host(source.host())
                .port(source.port())
                .database(source.name())
                .tablePrefix(source.tablePrefix())
                .username(source.username())
                .password(source.password())
                .pool(pool)
                .executor(executor)
                .migration(migration)
                .build();
        return Databases.mysql(config);
    }
}
