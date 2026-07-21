package com.stephanofer.networkpoints.synchronization;

import com.hera.craftkit.redis.RedisClient;
import com.hera.craftkit.redis.RedisClients;
import com.hera.craftkit.redis.RedisConfig;
import com.hera.craftkit.redis.RedisStartupMode;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.time.Duration;
import java.util.Objects;

public final class RedisFactory {
    private RedisFactory() {
    }

    public static RedisClient create(ConfigSnapshot.Redis source, String serverId) {
        Objects.requireNonNull(source, "source");
        RedisConfig config = RedisConfig.builder()
                .host(source.host())
                .port(source.port())
                .database(source.database())
                .username(source.username())
                .password(source.password())
                .ssl(source.ssl())
                .verifyPeer(source.verifyPeer())
                .environment(source.environment())
                .keyPrefix(source.keyPrefix())
                .serverId(serverId)
                .commandTimeout(Duration.ofMillis(source.commandTimeoutMillis()))
                .connectTimeout(Duration.ofMillis(source.connectTimeoutMillis()))
                .shutdownTimeout(Duration.ofMillis(source.shutdownTimeoutMillis()))
                .autoReconnect(source.autoReconnect())
                .build();
        return RedisClients.lettuce(config, RedisStartupMode.RECOVER);
    }
}
