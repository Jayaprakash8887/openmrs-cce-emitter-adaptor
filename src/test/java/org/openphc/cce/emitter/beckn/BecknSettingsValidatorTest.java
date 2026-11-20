package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;

import static org.junit.jupiter.api.Assertions.*;

class BecknSettingsValidatorTest {

    @Test
    void missingSettingsFailWithTheirNames() {
        EmitterProperties properties = new EmitterProperties();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        // CCE events leg (Sygnal)
        assertTrue(e.getMessage().contains("emitter.beckn.sygnal.network-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.sygnal.hie.uri"), e.getMessage());
        // Spice referral legs (Beckn)
        assertTrue(e.getMessage().contains("emitter.beckn.self.bap-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.self.bpp-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.spice.referrals-in.transaction-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.spice.referrals-out.contract-id"), e.getMessage());
        // these have defaults
        assertFalse(e.getMessage().contains("emitter.beckn.sygnal.id"), e.getMessage());
        assertFalse(e.getMessage().contains("emitter.beckn.sygnal.hie.id"), e.getMessage());
        assertFalse(e.getMessage().contains("caller-url"), e.getMessage());
        assertFalse(e.getMessage().contains("sygnal.hie.transaction-id"), e.getMessage());
    }

    @Test
    void onlyLegsThatAreOnAreChecked() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getLegs().setReferralsIn(false);
        properties.getBeckn().getLegs().setReferralsOut(false);
        properties.getBeckn().getSpice().setBapId(null);
        properties.getBeckn().getSpice().getReferralsOut().setContractId(null);
        properties.getBeckn().getSelf().setBapId(null);
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(properties.getBeckn()));
    }

    @Test
    void sygnalSettingsAreNotNeededWhenTheCceLegIsOff() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getLegs().setCceEvents(false);
        properties.getBeckn().getSygnal().setNetworkId(null);
        properties.getBeckn().getSygnal().getHie().setUri(null);
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(properties.getBeckn()));
    }

    @Test
    void referralsOutStillNeedsTheBapIdsWithoutTheCceLeg() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getLegs().setCceEvents(false);
        properties.getBeckn().getSelf().setBapUri(null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        assertTrue(e.getMessage().contains("emitter.beckn.self.bap-uri"), e.getMessage());
    }

    @Test
    void sygnalTransactionIdMustBeAUuidWhenSet() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSygnal().getHie().setTransactionId("hie-feed-1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        assertTrue(e.getMessage().contains("emitter.beckn.sygnal.hie.transaction-id"), e.getMessage());

        properties.getBeckn().getSygnal().getHie().setTransactionId("");
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(properties.getBeckn()));
    }

    @Test
    void hieUriMustBeAnAbsoluteHttpUrl() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getSygnal().getHie().setUri("hie.example.org/sygnal/receiver");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        assertTrue(e.getMessage().contains("emitter.beckn.sygnal.hie.uri"), e.getMessage());
    }

    @Test
    void completeSettingsPass() {
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(BecknTestSettings.properties().getBeckn()));
    }
}
