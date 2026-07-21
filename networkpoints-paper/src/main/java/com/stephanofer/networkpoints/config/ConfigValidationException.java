package com.stephanofer.networkpoints.config;

import java.util.List;

public final class ConfigValidationException extends Exception {
    private final List<String> errors;

    public ConfigValidationException(List<String> errors) {
        super("Invalid NetworkPoints configuration:" + System.lineSeparator() + String.join(System.lineSeparator(), errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
