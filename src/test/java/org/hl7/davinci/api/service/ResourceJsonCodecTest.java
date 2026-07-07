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

		byte[] stored = ResourceJsonCodec.encode(json);

		assertTrue(stored[0] == (byte) 0x1f && stored[1] == (byte) 0x8b, "encoded body is raw gzip");
		assertTrue(stored.length < json.length(), "encoding should shrink repetitive FHIR JSON");
		assertEquals(json, ResourceJsonCodec.decode(stored));
	}

	@Test
	void nullsPassThrough() {
		assertNull(ResourceJsonCodec.encode(null));
		assertNull(ResourceJsonCodec.decode(null));
	}
}
