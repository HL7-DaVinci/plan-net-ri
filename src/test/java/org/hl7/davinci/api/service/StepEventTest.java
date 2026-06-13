package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StepEventTest {

	@Test
	void failureKeepsTheBodyAndTruncatesOversizedOnes() {
		assertEquals(
				"{\"resourceType\":\"OperationOutcome\"}",
				StepEvent.failure("SEARCH", "m", "GET", "u", 500, 1L, "{\"resourceType\":\"OperationOutcome\"}")
						.errorBody());

		String oversized = "x".repeat(StepEvent.MAX_ERROR_BODY_CHARS + 1);
		String stored = StepEvent.failure("SEARCH", "m", "GET", "u", 500, 1L, oversized)
				.errorBody();
		assertTrue(stored.endsWith("... [truncated]"), "oversized bodies should be truncated with a marker");
		assertTrue(stored.length() < oversized.length() + 50);
	}

	@Test
	void failureDropsBlankBodies() {
		assertNull(StepEvent.failure("SEARCH", "m", "GET", "u", 500, 1L, null).errorBody());
		assertNull(StepEvent.failure("SEARCH", "m", "GET", "u", 500, 1L, " ").errorBody());
	}
}
