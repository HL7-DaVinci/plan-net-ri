package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A retained directory snapshot. output[] is derived at serve time from the files under storageDir. */
@Entity
@Table(
		name = "manifest",
		indexes = {@Index(name = "idx_manifest_job", columnList = "jobId")})
public class ManifestRecord {

	@Id
	private String id;

	private String jobId;

	/** Denormalized job name at build time, so the snapshot survives job renames/deletes. */
	private String jobName;

	private String batchId;

	@Enumerated(EnumType.STRING)
	private CrawlStrategy strategy;

	private Instant transactionTime;

	private boolean requiresAccessToken;

	/** Deprecated Bulk Data kick-off URL; null for SEARCH (no single kick-off request). */
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "request")
	private String request;

	private Instant generatedAt;

	private long totalResources;

	/** Absolute path to the directory holding this snapshot's {Type}.ndjson files. */
	private String storageDir;

	/** The incremental window anchor this snapshot covered (may be null for a full snapshot). */
	private String windowSince;

	/** Wall-clock time to build this snapshot: fetch + persist + NDJSON generation. */
	private long buildDurationMs;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}

	public CrawlStrategy getStrategy() {
		return strategy;
	}

	public void setStrategy(CrawlStrategy strategy) {
		this.strategy = strategy;
	}

	public Instant getTransactionTime() {
		return transactionTime;
	}

	public void setTransactionTime(Instant transactionTime) {
		this.transactionTime = transactionTime;
	}

	public boolean isRequiresAccessToken() {
		return requiresAccessToken;
	}

	public void setRequiresAccessToken(boolean requiresAccessToken) {
		this.requiresAccessToken = requiresAccessToken;
	}

	public String getRequest() {
		return request;
	}

	public void setRequest(String request) {
		this.request = request;
	}

	public Instant getGeneratedAt() {
		return generatedAt;
	}

	public void setGeneratedAt(Instant generatedAt) {
		this.generatedAt = generatedAt;
	}

	public long getTotalResources() {
		return totalResources;
	}

	public void setTotalResources(long totalResources) {
		this.totalResources = totalResources;
	}

	public String getStorageDir() {
		return storageDir;
	}

	public void setStorageDir(String storageDir) {
		this.storageDir = storageDir;
	}

	public String getWindowSince() {
		return windowSince;
	}

	public void setWindowSince(String windowSince) {
		this.windowSince = windowSince;
	}

	public long getBuildDurationMs() {
		return buildDurationMs;
	}

	public void setBuildDurationMs(long buildDurationMs) {
		this.buildDurationMs = buildDurationMs;
	}
}
