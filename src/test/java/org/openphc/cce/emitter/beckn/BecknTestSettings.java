package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;

/** The example settings of docs/beckn-integration.md § 11. */
final class BecknTestSettings {

    static final String SELF_ID = "bap.openmrs.nextsteps.mdtlabs.org";
    static final String HIE_ID = "bap.openhim.nextsteps.mdtlabs.org";
    static final String HIE_URI = "https://hie.example.org/sygnal/receiver";
    static final String NETWORK_ID = "sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry";
    static final String TRANSACTION_ID = "0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f";

    private BecknTestSettings() {
    }

    /** Complete settings, with a fixed transaction id. */
    static EmitterProperties properties() {
        EmitterProperties p = new EmitterProperties();
        p.setTarget(EmitterProperties.Target.BECKN);
        BecknConfig b = p.getBeckn();
        b.getSelf().setId(SELF_ID);
        b.getSelf().setNetworkId(NETWORK_ID);
        b.getHie().setId(HIE_ID);
        b.getHie().setUri(HIE_URI);
        b.getHie().setTransactionId(TRANSACTION_ID);
        return p;
    }
}
