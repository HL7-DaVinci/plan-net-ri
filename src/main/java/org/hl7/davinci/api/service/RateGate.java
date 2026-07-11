package org.hl7.davinci.api.service;

import java.util.concurrent.Semaphore;

/**
 * Per-server politeness brake shared by every crawl worker hitting that server, including
 * workers from different concurrently running jobs. Caps in-flight requests and, on a 429,
 * pauses every worker rather than just the one that got rate limited.
 */
final class RateGate {

	private final Semaphore inFlight;
	private volatile long pauseUntilNanos;

	RateGate(int maxInFlight) {
		this.inFlight = new Semaphore(Math.max(1, maxInFlight));
	}

	/** Push the pause deadline forward; never shortens an existing pause. */
	synchronized void pause(long millis) {
		long candidate = System.nanoTime() + millis * 1_000_000L;
		if (candidate > pauseUntilNanos) {
			pauseUntilNanos = candidate;
		}
	}

	/** Block the caller until any active pause has elapsed. */
	void awaitClearance() {
		while (true) {
			long remainingNanos = pauseUntilNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				return;
			}
			sleepInterruptibly(remainingNanos / 1_000_000L + 1);
		}
	}

	/** Acquire an in-flight slot, honoring any active pause first; release in a finally. */
	void acquire() {
		awaitClearance();
		try {
			inFlight.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted while waiting for the rate gate", e);
		}
	}

	void release() {
		inFlight.release();
	}

	/** True while a pause deadline is in the future. */
	boolean isPaused() {
		return pauseUntilNanos > System.nanoTime();
	}

	private static void sleepInterruptibly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Crawl interrupted while waiting for the rate gate", e);
		}
	}
}
