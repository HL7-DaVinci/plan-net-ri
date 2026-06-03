package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.hl7.davinci.api.service.CronSupport;
import org.junit.jupiter.api.Test;

class CronSupportTest {

	@Test
	void computesNextFutureRun() {
		Instant next = CronSupport.nextRun("0 0 * * * *");
		assertNotNull(next);
		assertTrue(next.isAfter(Instant.now()));
	}

	@Test
	void blankCronIsNull() {
		assertNull(CronSupport.nextRun(null));
		assertNull(CronSupport.nextRun("   "));
	}

	@Test
	void isValidAcceptsBlankAndWellFormedCron() {
		assertTrue(CronSupport.isValid(null));
		assertTrue(CronSupport.isValid("   "));
		assertTrue(CronSupport.isValid("0 0 3 * * *"));
	}

	@Test
	void isValidRejectsMalformedCron() {
		assertFalse(CronSupport.isValid("not a cron"));
		assertFalse(CronSupport.isValid("* * *"));
	}
}
