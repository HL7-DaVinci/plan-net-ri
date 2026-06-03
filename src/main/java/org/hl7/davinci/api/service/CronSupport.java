package org.hl7.davinci.api.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/** Computes the next run time from a Spring cron expression. */
public final class CronSupport {

	private CronSupport() {}

	/** Next fire time after now, or null if the expression is blank or never fires again. */
	public static Instant nextRun(String cron) {
		if (cron == null || cron.isBlank()) {
			return null;
		}
		ZonedDateTime next = CronExpression.parse(cron.trim()).next(ZonedDateTime.now(ZoneId.systemDefault()));
		return next != null ? next.toInstant() : null;
	}

	/** True when the expression is blank (no schedule) or a parseable Spring cron. */
	public static boolean isValid(String cron) {
		if (cron == null || cron.isBlank()) {
			return true;
		}
		try {
			CronExpression.parse(cron.trim());
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
