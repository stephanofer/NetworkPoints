package com.stephanofer.networkpoints.localization;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class LocalizedCatalog<T> {
    private final String defaultLanguage;
    private final Map<String, Map<String, List<T>>> catalogs;

    public LocalizedCatalog(String defaultLanguage, Map<String, Map<String, List<T>>> catalogs) {
        this.defaultLanguage = normalize(defaultLanguage);
        this.catalogs = Map.copyOf(catalogs);
        if (!this.catalogs.containsKey(this.defaultLanguage)) {
            throw new IllegalArgumentException("Default language catalog is missing");
        }
    }

    public List<T> actions(String language, String key) {
        Objects.requireNonNull(key, "key");
        Map<String, List<T>> selected = this.catalogs.getOrDefault(normalize(language), this.catalogs.get(this.defaultLanguage));
        List<T> actions = selected.get(key);
        if (actions != null) {
            return actions;
        }
        return this.catalogs.get(this.defaultLanguage).getOrDefault(key, List.of());
    }

    private static String normalize(String language) {
        return Objects.requireNonNullElse(language, "").trim().toLowerCase(Locale.ROOT);
    }
}
