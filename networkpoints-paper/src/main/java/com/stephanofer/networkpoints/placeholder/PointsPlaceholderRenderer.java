package com.stephanofer.networkpoints.placeholder;

import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class PointsPlaceholderRenderer {
    private final NetworkPointsService points;
    private final Supplier<ConfigSnapshot> configuration;

    public PointsPlaceholderRenderer(NetworkPointsService points, Supplier<ConfigSnapshot> configuration) {
        this.points = points;
        this.configuration = configuration;
    }

    public String render(UUID playerId, String parameter) {
        ConfigSnapshot snapshot = this.configuration.get();
        String key = parameter.toLowerCase(Locale.ROOT);
        if (key.equals("currency_symbol")) {
            return snapshot.reloadable().currency().symbol();
        }
        if (playerId == null) {
            return snapshot.reloadable().placeholderApi().unavailableValue();
        }
        var balance = this.points.cachedBalance(playerId);
        if (balance.isEmpty()) {
            return snapshot.reloadable().placeholderApi().unavailableValue();
        }
        BigDecimal amount = balance.orElseThrow().balance();
        return switch (key) {
            case "balance" -> this.points.formatAmountPlain(amount,
                    AmountDisplayMode.valueOf(snapshot.reloadable().amountFormat().defaultMode().name()));
            case "balance_raw" -> this.points.formatAmountPlain(amount, AmountDisplayMode.RAW);
            case "balance_grouped" -> this.points.formatAmountPlain(amount, AmountDisplayMode.GROUPED);
            case "balance_compact" -> this.points.formatAmountPlain(amount, AmountDisplayMode.COMPACT);
            case "balance_display" -> MiniMessage.miniMessage().serialize(this.points.formatAmount(amount));
            case "currency_name" -> amount.compareTo(BigDecimal.ONE) == 0
                    ? snapshot.reloadable().currency().singularName()
                    : snapshot.reloadable().currency().pluralName();
            default -> null;
        };
    }
}
