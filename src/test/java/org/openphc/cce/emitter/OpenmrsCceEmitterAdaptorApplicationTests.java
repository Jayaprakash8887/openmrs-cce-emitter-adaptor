package org.openphc.cce.emitter;

import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.beckn.BecknTargetForwarder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** The default target on this branch is beckn: the context loads with the example ids of the beckn-demo profile. */
@SpringBootTest
@ActiveProfiles("beckn-demo")
@TestPropertySource(properties = {
        "emitter.openmrs.base-url=http://localhost:8080/openmrs",
        "emitter.openhim.base-url=http://localhost:5001/fhir",
        "emitter.polling.fhir.enabled=false"
})
class OpenmrsCceEmitterAdaptorApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void becknIsTheDefaultTarget() {
        org.junit.jupiter.api.Assertions.assertFalse(context.getBeansOfType(BecknTargetForwarder.class).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(context.containsBean("onix"));
        org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("openhim"));
    }
}
