package com.stephanofer.networkpoints.identity;

import com.stephanofer.networkpoints.config.ConfigSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentityComposerTest {
    private static final ConfigSnapshot.Identity CONFIG = new ConfigSnapshot.Identity(
            "<prefix><nick><country_flag>", " ", " ", ConfigSnapshot.PrefixFormat.MINIMESSAGE);

    @Test
    void addsSeparatorsOnlyForPresentOptionalParts() {
        IdentityComposer composer = new IdentityComposer();

        assertEquals("[VIP] Alex [AR]", plain(composer.compose(CONFIG,
                Component.text("[VIP]"), Component.text("Alex"), Component.text("[AR]"))));
        assertEquals("Alex", plain(composer.compose(CONFIG,
                Component.empty(), Component.text("Alex"), Component.empty())));
        assertEquals("Alex [AR]", plain(composer.compose(CONFIG,
                Component.empty(), Component.text("Alex"), Component.text("[AR]"))));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
