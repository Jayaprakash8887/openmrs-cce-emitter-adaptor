package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;
import org.openphc.cce.emitter.config.EmitterProperties.BecknContractConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the {@code emitter.beckn.*} settings that Beckn mode needs. Only the settings of the legs
 * that are on are required.
 */
public final class BecknSettingsValidator {

    private BecknSettingsValidator() {
    }

    public static void validate(BecknConfig beckn) {
        List<String> missing = new ArrayList<>();
        var self = beckn.getSelf();
        var peer = beckn.getSpice();
        var legs = beckn.getLegs();

        require(missing, "emitter.beckn.schema-base-url", beckn.getSchemaBaseUrl());
        if (beckn.getOnix().getTimeout() == null || beckn.getOnix().getTimeout().isNegative()
                || beckn.getOnix().getTimeout().isZero()) {
            missing.add("emitter.beckn.onix.timeout (must be > 0)");
        }
        if (legs.isCceEvents() || legs.isReferralsOut()) {
            require(missing, "emitter.beckn.onix.bap-caller-url", beckn.getOnix().getBapCallerUrl());
            require(missing, "emitter.beckn.self.bap-id", self.getBapId());
            require(missing, "emitter.beckn.self.bap-uri", self.getBapUri());
        }
        if (legs.isCceEvents()) {
            var hie = beckn.getOpenhim();
            require(missing, "emitter.beckn.openhim.bpp-id", hie.getBppId());
            require(missing, "emitter.beckn.openhim.bpp-uri", hie.getBppUri());
            require(missing, "emitter.beckn.openhim.contract-id", hie.getContractId());
            require(missing, "emitter.beckn.openhim.transaction-id", hie.getTransactionId());
            require(missing, "emitter.beckn.openhim.commitment-file", hie.getCommitmentFile());
        }
        if (legs.isReferralsIn()) {
            require(missing, "emitter.beckn.onix.bpp-caller-url", beckn.getOnix().getBppCallerUrl());
            require(missing, "emitter.beckn.self.bpp-id", self.getBppId());
            require(missing, "emitter.beckn.self.bpp-uri", self.getBppUri());
            require(missing, "emitter.beckn.spice.bap-id", peer.getBapId());
            require(missing, "emitter.beckn.spice.bap-uri", peer.getBapUri());
            requireContract(missing, "emitter.beckn.spice.referrals-in", peer.getReferralsIn());
        }
        if (legs.isReferralsOut()) {
            require(missing, "emitter.beckn.spice.bpp-id", peer.getBppId());
            require(missing, "emitter.beckn.spice.bpp-uri", peer.getBppUri());
            requireContract(missing, "emitter.beckn.spice.referrals-out", peer.getReferralsOut());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("OPENMRS_EMITTER_TARGET=beckn but these Beckn settings are missing: "
                    + String.join(", ", missing) + " (see docs/beckn-integration.md § 11)");
        }
    }

    private static void requireContract(List<String> missing, String prefix, BecknContractConfig contract) {
        require(missing, prefix + ".contract-id", contract.getContractId());
        require(missing, prefix + ".transaction-id", contract.getTransactionId());
        require(missing, prefix + ".commitment-file", contract.getCommitmentFile());
    }

    private static void require(List<String> missing, String name, String value) {
        if (value == null || value.isBlank()) missing.add(name);
    }
}
