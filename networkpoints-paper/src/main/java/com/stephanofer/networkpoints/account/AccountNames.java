package com.stephanofer.networkpoints.account;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class AccountNames {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private AccountNames() {
    }

    public static String validate(String name) {
        Objects.requireNonNull(name, "name");
        if (!PLAYER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name must be a valid Java player name");
        }
        return name;
    }

    public static String normalize(String name) {
        return validate(name).toLowerCase(Locale.ROOT);
    }
}
