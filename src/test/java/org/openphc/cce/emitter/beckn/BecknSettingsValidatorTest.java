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
        assertTrue(e.getMessage().contains("emitter.beckn.self.network-id"), e.getMessage());
        assertTrue(e.getMessage().contains("emitter.beckn.hie.uri"), e.getMessage());
        // these have defaults
        assertFalse(e.getMessage().contains("emitter.beckn.self.id"), e.getMessage());
        assertFalse(e.getMessage().contains("emitter.beckn.hie.id"), e.getMessage());
        assertFalse(e.getMessage().contains("emitter.beckn.onix.caller-url"), e.getMessage());
        assertFalse(e.getMessage().contains("transaction-id"), e.getMessage());
    }

    @Test
    void transactionIdMustBeAUuidWhenSet() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getHie().setTransactionId("hie-feed-1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        assertTrue(e.getMessage().contains("emitter.beckn.hie.transaction-id"), e.getMessage());

        properties.getBeckn().getHie().setTransactionId("");
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(properties.getBeckn()));
    }

    @Test
    void hieUriMustBeAnAbsoluteHttpUrl() {
        EmitterProperties properties = BecknTestSettings.properties();
        properties.getBeckn().getHie().setUri("hie.example.org/sygnal/receiver");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BecknSettingsValidator.validate(properties.getBeckn()));
        assertTrue(e.getMessage().contains("emitter.beckn.hie.uri"), e.getMessage());
    }

    @Test
    void hieSettingsAreNotNeededWhenTheLegIsOff() {
        EmitterProperties properties = new EmitterProperties();
        properties.getBeckn().getLegs().setCceEvents(false);
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(properties.getBeckn()));
    }

    @Test
    void completeSettingsPass() {
        assertDoesNotThrow(() -> BecknSettingsValidator.validate(BecknTestSettings.properties().getBeckn()));
    }
}
