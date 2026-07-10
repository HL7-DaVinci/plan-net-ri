package org.hl7.davinci.publish.feed;

import static org.hl7.davinci.publish.feed.WriteFrontier.computeFrontier;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WriteFrontierTest {

	@Test
	void noPinsFallsBackToTheGraceWindow() {
		long now = 100_000L;
		long grace = 5_000L;

		assertEquals(now - grace, computeFrontier(now, grace, null, 0L));
	}

	@Test
	void pinOlderThanTheGraceWindowWins() {
		long now = 100_000L;
		long grace = 5_000L;
		long minPin = 90_000L; // older than now - grace (95_000)

		assertEquals(minPin - 1, computeFrontier(now, grace, minPin, 0L));
	}

	@Test
	void pinNewerThanTheGraceWindowLeavesTheGraceBoundInPlace() {
		long now = 100_000L;
		long grace = 5_000L;
		long minPin = 99_000L; // newer than now - grace (95_000)

		assertEquals(now - grace, computeFrontier(now, grace, minPin, 0L));
	}

	@Test
	void resultNeverDropsBelowTheFloor() {
		long now = 100_000L;
		long grace = 5_000L;
		long minPin = 50_000L; // would compute to 49_999, below the floor
		long floor = 90_000L;

		assertEquals(floor, computeFrontier(now, grace, minPin, floor));
	}

	@Test
	void minPinExactlyAtTheGraceBoundaryStillYieldsThePinBound() {
		long now = 100_000L;
		long grace = 5_000L;
		long minPin = now - grace; // exactly 95_000, tying the grace bound

		assertEquals(minPin - 1, computeFrontier(now, grace, minPin, 0L));
	}
}
