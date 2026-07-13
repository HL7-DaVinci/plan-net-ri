package org.hl7.davinci.api.entity;

/** Status of a crawl run; RUNNING is the only non-terminal state. */
public enum RunStatus {
	/** In flight. A RUNNING row with no live worker is a crash artifact; startup recovery converts it to PAUSED. */
	RUNNING,
	COMPLETED,
	ABORTED,
	/** Stopped on user request with checkpoints retained; a later run continues from them. */
	PAUSED,
	ERROR
}
