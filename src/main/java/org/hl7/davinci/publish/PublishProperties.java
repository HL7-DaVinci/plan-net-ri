package org.hl7.davinci.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code publish.*}: periodic self-publish of this server's own Plan-Net data via the
 * Bulk Data $bulk-publish operation. Bean name {@code publishProperties} is referenced by SpEL in
 * {@link PublishService}.
 */
@Component
@ConfigurationProperties(prefix = "publish")
public class PublishProperties {

	private boolean enabled = true;

	private long intervalMs = 60_000;

	/** Grace period the write frontier holds below the wall clock when no write is pinning it lower. */
	private long frontierGraceMs = 5_000;

	/** How far behind the previous snapshot's transactionTime a steady tick's change-feed window starts. */
	private long overlapMs = 60_000;

	/** Snapshot directories kept on disk after a publish; the grace period for prior file URLs. */
	private int retention = 3;

	/** Floor under retention: a snapshot younger than this is never pruned regardless of the count window. */
	private long gracePeriodMs = 21_600_000;

	/** Resources read per page during a type's streaming export. */
	private int exportPageSize = 1000;

	/** Directory under which snapshot directories are written; separate from the crawler's storage. */
	private String storagePath = "./target/publish-data";

	/**
	 * Wipe the storage path on startup before the first publish. Default true, matching the
	 * ephemeral hosted DB; false lets a surviving {@code current} snapshot keep serving across a
	 * restart.
	 */
	private boolean resetOnStartup = true;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getIntervalMs() {
		return intervalMs;
	}

	public void setIntervalMs(long intervalMs) {
		this.intervalMs = intervalMs;
	}

	public long getFrontierGraceMs() {
		return frontierGraceMs;
	}

	public void setFrontierGraceMs(long frontierGraceMs) {
		this.frontierGraceMs = frontierGraceMs;
	}

	public long getOverlapMs() {
		return overlapMs;
	}

	public void setOverlapMs(long overlapMs) {
		this.overlapMs = overlapMs;
	}

	public int getRetention() {
		return retention;
	}

	public void setRetention(int retention) {
		this.retention = retention;
	}

	public long getGracePeriodMs() {
		return gracePeriodMs;
	}

	public void setGracePeriodMs(long gracePeriodMs) {
		this.gracePeriodMs = gracePeriodMs;
	}

	public int getExportPageSize() {
		return exportPageSize;
	}

	public void setExportPageSize(int exportPageSize) {
		this.exportPageSize = exportPageSize;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public void setStoragePath(String storagePath) {
		this.storagePath = storagePath;
	}

	public boolean isResetOnStartup() {
		return resetOnStartup;
	}

	public void setResetOnStartup(boolean resetOnStartup) {
		this.resetOnStartup = resetOnStartup;
	}
}
