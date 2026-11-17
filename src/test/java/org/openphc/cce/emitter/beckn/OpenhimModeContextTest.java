package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/** With target=openhim no Beckn bean exists and no Beckn setting is required. */
@SpringBootTest(properties = {
        "emitter.target=openhim",
        "emitter.openmrs.base-url=http://localhost:8080/openmrs",
        "emitter.openhim.base-url=http://localhost:5001/fhir",
        "emitter.polling.fhir.enabled=false"
})
class OpenhimModeContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noBecknBeans() {
        assertTrue(context.getBeansOfType(BecknTargetForwarder.class).isEmpty());
        assertTrue(context.getBeansOfType(OnixClient.class).isEmpty());
        assertTrue(context.containsBean("openhim"));
        assertFalse(context.containsBean("onix"));
    }
}
