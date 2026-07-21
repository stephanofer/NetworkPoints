package com.stephanofer.networkpoints.payment;

import com.stephanofer.networkpoints.api.NetworkPointsService;
import com.stephanofer.networkpoints.api.amount.AmountDisplayMode;
import com.stephanofer.networkpoints.config.ConfigSnapshot;
import com.stephanofer.networkpoints.feedback.FeedbackService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

public final class PaymentDialogFactory {
    private final NetworkPointsService points;
    private final FeedbackService feedback;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PaymentDialogFactory(NetworkPointsService points, FeedbackService feedback) {
        this.points = Objects.requireNonNull(points, "points");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    public Dialog create(Player player, PaymentSession session, Component sender, Component recipient,
                         ConfigSnapshot.Reloadable config, Consumer<Player> confirm, Consumer<Player> cancel) {
        ConfigSnapshot.PaymentDialogText text = localized(config.paymentDialogs(), this.feedback.resolvedLanguage(player));
        Duration lifetime = Duration.between(java.time.Instant.now(), session.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()) {
            lifetime = Duration.ofMillis(1);
        }
        ClickCallback.Options options = ClickCallback.Options.builder().uses(1).lifetime(lifetime).build();
        Component compact = Component.text(this.points.formatAmountPlain(session.amount(), AmountDisplayMode.COMPACT)
                + " " + config.currency().symbol());
        String currencyName = session.amount().compareTo(BigDecimal.ONE) == 0
                ? config.currency().singularName() : config.currency().pluralName();
        Component exact = Component.text(exact(session.amount(), config.amountFormat())
                + " " + currencyName);
        Component body = this.miniMessage.deserialize(text.body(),
                Placeholder.component("sender", sender),
                Placeholder.component("recipient", recipient),
                Placeholder.component("compact_amount", compact),
                Placeholder.component("exact_amount", exact));
        ActionButton confirmButton = ActionButton.builder(this.miniMessage.deserialize(text.confirmLabel()))
                .tooltip(this.miniMessage.deserialize(text.confirmTooltip()))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player clicked) {
                        confirm.accept(clicked);
                    }
                }, options)).build();
        ActionButton cancelButton = ActionButton.builder(this.miniMessage.deserialize(text.cancelLabel()))
                .tooltip(this.miniMessage.deserialize(text.cancelTooltip()))
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player clicked) {
                        cancel.accept(clicked);
                    }
                }, options)).build();
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(this.miniMessage.deserialize(text.title()))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .build())
                .type(DialogType.confirmation(confirmButton, cancelButton)));
    }

    private static ConfigSnapshot.PaymentDialogText localized(
            Map<String, ConfigSnapshot.PaymentDialogText> dialogs, String language) {
        return dialogs.getOrDefault(language.toLowerCase(Locale.ROOT), dialogs.get("en"));
    }

    private static String exact(BigDecimal amount, ConfigSnapshot.AmountFormat format) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(format.groupingSeparator());
        symbols.setDecimalSeparator(format.decimalSeparator());
        DecimalFormat decimal = new DecimalFormat("#,##0.00", symbols);
        decimal.setParseBigDecimal(true);
        return decimal.format(amount);
    }
}
