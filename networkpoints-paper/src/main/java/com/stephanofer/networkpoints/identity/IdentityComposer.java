package com.stephanofer.networkpoints.identity;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public final class IdentityComposer {
    public Component compose(ConfigSnapshot.Identity config, Component prefix, Component nick, Component flag) {
        Component spacedPrefix = isEmpty(prefix) ? Component.empty() : prefix.append(Component.text(config.prefixSuffix()));
        Component spacedFlag = isEmpty(flag) ? Component.empty() : Component.text(config.flagPrefix()).append(flag);
        return MiniMessage.miniMessage().deserialize(config.format(),
                Placeholder.component("prefix", spacedPrefix),
                Placeholder.component("nick", nick),
                Placeholder.component("country_flag", spacedFlag));
    }

    private static boolean isEmpty(Component component) {
        return component.equals(Component.empty());
    }
}
