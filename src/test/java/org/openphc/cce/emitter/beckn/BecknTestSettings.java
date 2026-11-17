package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;

import java.util.List;

/** The example settings of docs/beckn-integration.md § 11. */
final class BecknTestSettings {

    private BecknTestSettings() {
    }

    static EmitterProperties properties() {
        EmitterProperties p = new EmitterProperties();
        p.setTarget(EmitterProperties.Target.BECKN);
        BecknConfig b = p.getBeckn();
        b.getSelf().setBapId("bap.openmrs.example.org");
        b.getSelf().setBapUri("https://openmrs.example.org/bap/receiver");
        b.getSelf().setBppId("bpp.openmrs.example.org");
        b.getSelf().setBppUri("https://openmrs.example.org/bpp/receiver");
        b.getOpenhim().setBppId("bpp.openhim.example.org");
        b.getOpenhim().setBppUri("https://hie.example.org/bpp/receiver");
        b.getOpenhim().setContractId("5b1e7f3c-2a4d-4c6e-8f90-a1b2c3d4e5f6");
        b.getOpenhim().setTransactionId("0d5f8c1a-7b2e-4f6d-9a3c-1e2b3c4d5e6f");
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
