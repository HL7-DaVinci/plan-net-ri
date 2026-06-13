package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceJsonCodecTest {

	@Test
	void roundTripsAndCompresses() {
		String json = "{\"resourceType\":\"Organization\",\"id\":\"a\",\"name\":\""
				+ "x".repeat(2_000) + "\"}";

		String stored = ResourceJsonCodec.encode(json);

		assertTrue(stored.startsWith("gz:"), "encoded values carry the marker prefix");
		assertTrue(stored.length() < json.length(), "encoding should shrink repetitive FHIR JSON");
		assertEquals(json, ResourceJsonCodec.decode(stored));
	}

	@Test
	void legacyPlaintextRowsAreReadInPlace() {
		String legacy = "{\"resourceType\":\"Organization\",\"id\":\"a\"}";
		assertEquals(legacy, ResourceJsonCodec.decode(legacy), "pre-compression rows must pass through unchanged");
	}

	@Test
	void nullsPassThrough() {
		assertNull(ResourceJsonCodec.encode(null));
		assertNull(ResourceJsonCodec.decode(null));
	}
}
