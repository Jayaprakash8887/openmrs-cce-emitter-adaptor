package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;
import org.openphc.cce.emitter.config.EmitterProperties.BecknContractConfig;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Checks the {@code emitter.beckn.*} settings that Beckn mode needs. Only the settings of the legs
 * that are on are required: {@code emitter.beckn.sygnal.*} for the CCE events leg (Sygnal), and the
 * Beckn ids, contracts and callers for the Spice referral legs.
 */
public final class BecknSettingsValidator {

    private BecknSettingsValidator() {
    }

    public static void validate(BecknConfig beckn) {
        List<String> problems = new ArrayList<>();
        var self = beckn.getSelf();
        var peer = beckn.getSpice();
        var legs = beckn.getLegs();

        require(problems, "emitter.beckn.schema-base-url", beckn.getSchemaBaseUrl());
        if (beckn.getOnix().getTimeout() == null || beckn.getOnix().getTimeout().isNegative()
                || beckn.getOnix().getTimeout().isZero()) {
            problems.add("emitter.beckn.onix.timeout (must be > 0)");
        }
        if (legs.isCceEvents()) {
            var sygnal = beckn.getSygnal();
            var hie = sygnal.getHie();
            require(problems, "emitter.beckn.sygnal.caller-url", sygnal.getCallerUrl());
            require(problems, "emitter.beckn.sygnal.id", sygnal.getId());
            require(problems, "emitter.beckn.sygnal.network-id", sygnal.getNetworkId());
            require(problems, "emitter.beckn.sygnal.hie.id", hie.getId());
            if (require(problems, "emitter.beckn.sygnal.hie.uri", hie.getUri()) && !isHttpUrl(hie.getUri())) {
                problems.add("emitter.beckn.sygnal.hie.uri (must be an absolute http(s) URL)");
            }
            if (!isBlank(hie.getTransactionId()) && !isUuid(hie.getTransactionId().trim())) {
                problems.add("emitter.beckn.sygnal.hie.transaction-id (must be a UUID, or empty for a new one per event)");
            }
        }
        if (legs.isReferralsOut()) {
            require(problems, "emitter.beckn.onix.bap-caller-url", beckn.getOnix().getBapCallerUrl());
            require(problems, "emitter.beckn.self.bap-id", self.getBapId());
            require(problems, "emitter.beckn.self.bap-uri", self.getBapUri());
        }
        if (legs.isReferralsIn()) {
            require(problems, "emitter.beckn.onix.bpp-caller-url", beckn.getOnix().getBppCallerUrl());
            require(problems, "emitter.beckn.self.bpp-id", self.getBppId());
            require(problems, "emitter.beckn.self.bpp-uri", self.getBppUri());
            require(problems, "emitter.beckn.spice.bap-id", peer.getBapId());
            require(problems, "emitter.beckn.spice.bap-uri", peer.getBapUri());
            requireContract(problems, "emitter.beckn.spice.referrals-in", peer.getReferralsIn());
        }
        if (legs.isReferralsOut()) {
            require(problems, "emitter.beckn.spice.bpp-id", peer.getBppId());
            require(problems, "emitter.beckn.spice.bpp-uri", peer.getBppUri());
            requireContract(problems, "emitter.beckn.spice.referrals-out", peer.getReferralsOut());
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("OPENMRS_EMITTER_TARGET=beckn but these Beckn settings are missing or wrong: "
                    + String.join(", ", problems) + " (see docs/beckn-integration.md § 11)");
        }
    }

    private static void requireContract(List<String> problems, String prefix, BecknContractConfig contract) {
        require(problems, prefix + ".contract-id", contract.getContractId());
        require(problems, prefix + ".transaction-id", contract.getTransactionId());
        require(problems, prefix + ".commitment-file", contract.getCommitmentFile());
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
