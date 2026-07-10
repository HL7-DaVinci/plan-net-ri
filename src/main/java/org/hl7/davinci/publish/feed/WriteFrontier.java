package org.hl7.davinci.publish.feed;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.davinci.publish.PublishProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks in-flight write transactions so {@link #frontierMillis()} returns an instant at or before
 * which every row is guaranteed committed and visible.
 */
@Interceptor
@Component
public class WriteFrontier {

	private static final Logger ourLog = LoggerFactory.getLogger(WriteFrontier.class);
	private static final long CLOCK_REGRESSION_WARN_THROTTLE_MS = 60_000;

	private final PublishProperties publishProperties;

	/** Keyed by identity: {@link TransactionDetails} does not override equals/hashCode. */
	private final ConcurrentHashMap<TransactionDetails, Long> pins = new ConcurrentHashMap<>();

	/** Transactions registered outside an active Spring transaction, awaiting PRECOMMIT cleanup. */
	private final Set<TransactionDetails> pendingManualCleanup = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private final AtomicLong lastReturnedFrontier = new AtomicLong(Long.MIN_VALUE);
	private final AtomicLong lastPublishedMillis = new AtomicLong(Long.MIN_VALUE);
	private final AtomicLong lastClockRegressionWarnAt = new AtomicLong(Long.MIN_VALUE);
	private final AtomicLong totalRegistrations = new AtomicLong();

	public WriteFrontier(PublishProperties publishProperties) {
		this.publishProperties = publishProperties;
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void preStorageCreated(TransactionDetails theTransactionDetails) {
		register(theTransactionDetails);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	public void preStorageUpdated(TransactionDetails theTransactionDetails) {
		register(theTransactionDetails);
	}

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_DELETED)
	public void preStorageDeleted(TransactionDetails theTransactionDetails) {
		register(theTransactionDetails);
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void preCommitCreated(TransactionDetails theTransactionDetails) {
		registerAndCleanUpIfUnsynchronized(theTransactionDetails);
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
	public void preCommitUpdated(TransactionDetails theTransactionDetails) {
		registerAndCleanUpIfUnsynchronized(theTransactionDetails);
	}

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
	public void preCommitDeleted(TransactionDetails theTransactionDetails) {
		registerAndCleanUpIfUnsynchronized(theTransactionDetails);
	}

	/** Fallback cleanup for a registration that had no active transaction to hook afterCompletion. */
	private void registerAndCleanUpIfUnsynchronized(TransactionDetails theTransactionDetails) {
		register(theTransactionDetails);
		if (pendingManualCleanup.remove(theTransactionDetails)) {
			pins.remove(theTransactionDetails);
		}
	}

	private void register(TransactionDetails theTransactionDetails) {
		long stamp = theTransactionDetails.getTransactionDate().getTime();
		if (pins.putIfAbsent(theTransactionDetails, stamp) != null) {
			return;
		}
		totalRegistrations.incrementAndGet();

		long publishedAt = lastPublishedMillis.get();
		if (stamp <= publishedAt) {
			ourLog.warn(
					"Write frontier registration arrived at or before the last published transactionTime "
							+ "(stamp={}, lastPublished={}); the corresponding row may have already been "
							+ "excluded from a published snapshot",
					stamp,
					publishedAt);
		}

		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					pins.remove(theTransactionDetails);
				}
			});
		} else {
			pendingManualCleanup.add(theTransactionDetails);
		}
	}

	/**
	 * The current write frontier in epoch millis: the newest instant with no in-flight or
	 * unregistered write at or before it. Monotonic; never decreases.
	 */
	public long frontierMillis() {
		long now = System.currentTimeMillis();
		long graceMs = publishProperties.getFrontierGraceMs();
		Long minPin = minPinMillis();

		long previousFloor = lastReturnedFrontier.get();
		if (now < previousFloor) {
			warnClockRegression(now, previousFloor);
		}

		return lastReturnedFrontier.updateAndGet(floor -> computeFrontier(now, graceMs, minPin, floor));
	}

	public int inFlightCount() {
		return pins.size();
	}

	/** Count of writes registered since startup. */
	public long totalRegistrations() {
		return totalRegistrations.get();
	}

	/** Records the transactionTime of the most recent publish. */
	public void noteLastPublished(long transactionTimeMillis) {
		lastPublishedMillis.set(transactionTimeMillis);
	}

	private Long minPinMillis() {
		Long min = null;
		for (Long value : pins.values()) {
			if (min == null || value < min) {
				min = value;
			}
		}
		return min;
	}

	private void warnClockRegression(long now, long floor) {
		long last = lastClockRegressionWarnAt.get();
		if (now - last >= CLOCK_REGRESSION_WARN_THROTTLE_MS && lastClockRegressionWarnAt.compareAndSet(last, now)) {
			ourLog.warn(
					"System clock appears to have regressed: now={} is before the previously computed write "
							+ "frontier floor={}",
					now,
					floor);
		}
	}

	static long computeFrontier(long nowMs, long graceMs, Long minPinMs, long floorMs) {
		long minPinBound = (minPinMs == null) ? Long.MAX_VALUE : minPinMs - 1;
		long graceBound = nowMs - graceMs;
		return Math.max(floorMs, Math.min(graceBound, minPinBound));
	}
}
