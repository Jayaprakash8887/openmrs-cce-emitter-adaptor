package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;

import static org.junit.jupiter.api.Assertions.*;

class BecknSettingsValidatorTest {

    @Test
    void missingIdsFailWithTheirNames() {
        EmitterProperties properties = new EmitterProperties();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        assertTrue(e.getMessage().contains("emitter.beckn.self.bap-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.openhim.contract-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.spice.referrals-in.transaction-id"), e.getMessage());
    }

    @Test
    void onlyLegsThatAreOnAreChecked() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getLegs().setReferralsIn(false);
        properties.getBeckn().getLegs().setReferralsOut(false);
        properties.getBeckn().getSpice().setBapId(null);
        properties.getBeckn().getSpice().getReferralsOut().setContractId(null);
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(properties.getBeckn()));
    }

    @Test
    void completeSettingsPass() {
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(BecknTestSettings.properties().getBeckn()));
    }
}
