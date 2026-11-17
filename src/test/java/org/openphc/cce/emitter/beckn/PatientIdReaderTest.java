package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.config.EmitterProperties;

import static org.junit.jupiter.api.Assertions.*;

class PatientIdReaderTest {

    private final PatientIdReader reader = new PatientIdReader(new EmitterProperties());

    private String read(String json) {
        return reader.read(BecknJson.parse(json));
    }

    @Test
    void subjectReference() {
        assertEquals("1198503150001234",
                read("{\"resourceType\":\"Encounter\",\"subject\":{\"reference\":\"Patient/1198503150001234\"}}"));
    }

    @Test
    void patientReference() {
        assertEquals("119", read("{\"resourceType\":\"AllergyIntolerance\",\"patient\":{\"reference\":\"Patient/119\"}}"));
    }

    @Test
    void subjectThatIsNotAPatientIsIgnored() {
        assertNull(read("{\"resourceType\":\"Observation\",\"subject\":{\"reference\":\"Group/1\"}}"));
    }

    @Test
    void patientUsesNationalIdIdentifier() {
        assertEquals("NID-1", read("{\"resourceType\":\"Patient\",\"id\":\"other\",\"identifier\":["
                + "{\"system\":\"urn:x\",\"value\":\"nope\"},"
                + "{\"system\":\"http://moh.gov.rw/fhir/identifier/national-id\",\"value\":\"NID-1\"}]}"));
    }

    @Test
    void patientWithoutNationalIdentifierUsesItsId() {
        assertEquals("NID-2", read("{\"resourceType\":\"Patient\",\"id\":\"NID-2\"}"));
    }

    @Test
    void resourcesWithoutPatientGiveNull() {
        assertNull(read("{\"resourceType\":\"Location\",\"id\":\"l1\"}"));
        assertNull(read("{\"resourceType\":\"Practitioner\",\"id\":\"p1\"}"));
    }
}
