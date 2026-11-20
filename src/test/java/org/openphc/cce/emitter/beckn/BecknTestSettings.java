package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;

import java.util.List;

/** The example settings of docs/beckn-integration.md § 11. */
final class BecknTestSettings {

    static final String SYGNAL_ID = "bap.openmrs.nextsteps.mdtlabs.org";
    static final String HIE_ID = "bap.openhim.nextsteps.mdtlabs.org";
    static final String HIE_URI = "https://hie.example.org/sygnal/receiver";
    static final String NETWORK_ID = "sygnal.nextsteps.mdtlabs.org/sygnal_demo_network_registry";
    static final String TRANSACTION_ID = "0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f";

    private BecknTestSettings() {
    }

    /** Complete settings for all three legs; the Sygnal leg has a fixed transaction id. */
    static EmitterProperties properties() {
        EmitterProperties p = new EmitterProperties();
        p.setTarget(EmitterProperties.Target.BECKN);
        BecknConfig b = p.getBeckn();
        // CCE events leg (Sygnal)
        b.getSygnal().setId(SYGNAL_ID);
        b.getSygnal().setNetworkId(NETWORK_ID);
        b.getSygnal().getHie().setId(HIE_ID);
        b.getSygnal().getHie().setUri(HIE_URI);
        b.getSygnal().getHie().setTransactionId(TRANSACTION_ID);
        // Spice referral legs (Beckn)
        b.getSelf().setBapId("bap.openmrs.example.org");
        b.getSelf().setBapUri("https://openmrs.example.org/bap/receiver");
        b.getSelf().setBppId("bpp.openmrs.example.org");
        b.getSelf().setBppUri("https://openmrs.example.org/bpp/receiver");
        var peer = b.getSpice();
        peer.setBapId("bap.spice.example.org");
        peer.setBapUri("https://spice.example.org/bap/receiver");
        peer.setBppId("bpp.spice.example.org");
        peer.setBppUri("https://spice.example.org/bpp/receiver");
        peer.setFacilityCodes(List.of("0417"));
        peer.getReferralsIn().setContractId("e2c9a7b4-6f1d-4b3a-9c8e-7d6f5a4b3c2d");
        peer.getReferralsIn().setTransactionId("a41f6b2c-9d3e-4a7b-8c1d-2e3f4a5b6c7d");
        peer.getReferralsOut().setContractId("7c3d9e1f-5a2b-4d6c-8e7f-0a1b2c3d4e5f");
        peer.getReferralsOut().setTransactionId("b52a7c3d-0e4f-4b8c-9d2e-3f4a5b6c7d8e");
        return p;
    }
}
