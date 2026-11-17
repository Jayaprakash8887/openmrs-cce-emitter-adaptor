package org.openphc.cce.emitter.beckn;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventIdFactoryTest {

    private final EventIdFactory factory = new EventIdFactory();

    private String id(String json) {
        return factory.eventId(BecknJson.parse(json));
    }

    @Test
    void sameResourceGivesSameId() {
        String json = "{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"status\":\"finished\"}";
        assertEquals(id(json), id(json));
    }

    @Test
    void keyOrderAndWhitespaceDoNotChangeTheId() {
        String a = "{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"meta\":{\"versionId\":\"1\",\"lastUpdated\":\"2026-11-13T08:00:12Z\"},"
                + "\"subject\":{\"reference\":\"Patient/119\"}}";
        String b = "{ \"subject\": {\"reference\": \"Patient/119\"},\n \"meta\": {\"lastUpdated\":\"2026-11-13T08:00:12Z\",\"versionId\":\"1\"},"
                + " \"id\": \"e1\", \"resourceType\": \"Encounter\" }";
        assertEquals(id(a), id(b));
    }

    @Test
    void arrayOrderMatters() {
        String a = "{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"type\":[{\"text\":\"a\"},{\"text\":\"b\"}]}";
        String b = "{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"type\":[{\"text\":\"b\"},{\"text\":\"a\"}]}";
        assertNotEquals(id(a), id(b));
    }

    @Test
    void statusChangeGivesNewIdEvenWithSameLastUpdated() {
        String active = "{\"resourceType\":\"ServiceRequest\",\"id\":\"sr1\",\"meta\":{\"lastUpdated\":\"2026-11-16T09:30:10Z\"},\"status\":\"active\"}";
        String completed = "{\"resourceType\":\"ServiceRequest\",\"id\":\"sr1\",\"meta\":{\"lastUpdated\":\"2026-11-16T09:30:10Z\"},\"status\":\"completed\"}";
        assertNotEquals(id(active), id(completed));
    }

    @Test
    void differentResourcesWithSameBodyGetDifferentIds() {
        assertNotEquals(id("{\"resourceType\":\"Encounter\",\"id\":\"a\"}"),
                id("{\"resourceType\":\"Encounter\",\"id\":\"b\"}"));
    }

    @Test
    void decimalsAreKeptExactly() {
        assertNotEquals(id("{\"resourceType\":\"Observation\",\"id\":\"o\",\"v\":1.50}"),
                id("{\"resourceType\":\"Observation\",\"id\":\"o\",\"v\":1.5}"));
    }

    @Test
    void idIsAVersion5UuidOf36Characters() {
        String id = id("{\"resourceType\":\"Encounter\",\"id\":\"e1\"}");
        assertEquals(36, id.length());
        UUID uuid = UUID.fromString(id);
        assertEquals(5, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    void idIsFixedForAGivenResource() {
        // Guards the namespace and the algorithm: if this changes, every event gets a new id.
        String id = id("{\"resourceType\":\"Encounter\",\"id\":\"e1\",\"status\":\"finished\"}");
        assertEquals(id, factory.eventId(BecknJson.parse("{\"status\":\"finished\",\"id\":\"e1\",\"resourceType\":\"Encounter\"}")));
        assertEquals(UUID.fromString("6f1c9a52-3d8e-4b27-9e14-0a7c5d2b8f31"), EventIdFactory.NAMESPACE);
    }

    @Test
    void nameUuidV5MatchesRfcReference() {
        // Python: uuid.uuid5(uuid.NAMESPACE_DNS, "python.org")
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertEquals(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"), EventIdFactory.nameUuidV5(dns, "python.org"));
    }
}
