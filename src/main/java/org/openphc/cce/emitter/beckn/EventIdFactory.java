package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The event id of a resource (docs/beckn-integration.md § 5): the resource JSON with object keys
 * sorted at every level and no whitespace, hashed with SHA-256; the hex hash is the name of a
 * version-5 (name-based) UUID in {@link #NAMESPACE}.
 *
 * <p>The same resource always gets the same id; any change to it gives a new id.
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class EventIdFactory {

    /** Fixed namespace of the event ids. Never change it: every event would get a new id. */
    public static final UUID NAMESPACE = UUID.fromString("6f1c9a52-3d8e-4b27-9e14-0a7c5d2b8f31");

    public String eventId(JsonNode resource) {
        String canonical = BecknJson.write(canonical(resource));
        String hash = HexFormat.of().formatHex(digest("SHA-256", canonical.getBytes(StandardCharsets.UTF_8)));
        return nameUuidV5(NAMESPACE, hash).toString();
    }

    /** A copy of the tree with object keys sorted at every level. Arrays keep their order. */
    static JsonNode canonical(JsonNode node) {
        if (node == null) return BecknJson.MAPPER.nullNode();
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            it.forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = BecknJson.MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, canonical(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = BecknJson.MAPPER.createArrayNode();
            for (JsonNode element : node) {
                copy.add(canonical(element));
            }
            return copy;
        }
        return node;
    }

    /** RFC 4122 version-5 UUID: SHA-1 of namespace bytes + UTF-8 name. */
    static UUID nameUuidV5(UUID namespace, String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(16 + nameBytes.length);
        input.putLong(namespace.getMostSignificantBits());
        input.putLong(namespace.getLeastSignificantBits());
        input.put(nameBytes);
        byte[] sha1 = digest("SHA-1", input.array());
        sha1[6] = (byte) ((sha1[6] & 0x0f) | 0x50); // version 5
        sha1[8] = (byte) ((sha1[8] & 0x3f) | 0x80); // IETF variant
        ByteBuffer out = ByteBuffer.wrap(sha1, 0, 16);
        return new UUID(out.getLong(), out.getLong());
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }
}
