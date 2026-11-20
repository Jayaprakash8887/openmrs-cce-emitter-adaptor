package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Checks the {@code emitter.beckn.*} settings that Beckn (Sygnal) mode needs. The HIE settings are
 * required only when the {@code cce-events} leg is on.
 */
public final class BecknSettingsValidator {

    private BecknSettingsValidator() {
    }

    public static void validate(BecknConfig beckn) {
        List<String> problems = new ArrayList<>();
        var self = beckn.getSelf();
        var hie = beckn.getHie();

        require(problems, "emitter.beckn.schema-base-url", beckn.getSchemaBaseUrl());
        if (beckn.getOnix().getTimeout() == null || beckn.getOnix().getTimeout().isNegative()
                || beckn.getOnix().getTimeout().isZero()) {
            problems.add("emitter.beckn.onix.timeout (must be > 0)");
        }
        if (beckn.getLegs().isCceEvents()) {
            require(problems, "emitter.beckn.onix.caller-url", beckn.getOnix().getCallerUrl());
            require(problems, "emitter.beckn.self.id", self.getId());
            require(problems, "emitter.beckn.self.network-id", self.getNetworkId());
            require(problems, "emitter.beckn.hie.id", hie.getId());
            if (require(problems, "emitter.beckn.hie.uri", hie.getUri()) && !isHttpUrl(hie.getUri())) {
                problems.add("emitter.beckn.hie.uri (must be an absolute http(s) URL)");
            }
            if (!isBlank(hie.getTransactionId()) && !isUuid(hie.getTransactionId().trim())) {
                problems.add("emitter.beckn.hie.transaction-id (must be a UUID, or empty for a new one per event)");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("OPENMRS_EMITTER_TARGET=beckn but these Beckn settings are missing or wrong: "
                    + String.join(", ", problems) + " (see docs/beckn-integration.md § 11)");
        }
    }

    /** Adds the name when the value is blank. Returns true when the value is set. */
    private static boolean require(List<String> problems, String name, String value) {
        if (isBlank(value)) {
            problems.add(name);
            return false;
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
