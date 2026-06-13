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

/** One per-server crawl run; runs from one operation share a batchId. */
@Entity
@Table(
		name = "crawl_run",
		indexes = {
			@Index(name = "idx_crawl_run_job", columnList = "jobId"),
			@Index(name = "idx_crawl_run_job_server", columnList = "jobId,serverKey")
		})
public class CrawlRun {

	@Id
	private String id;

	private String jobId;

	private String batchId;

	private String serverKey;

	private String serverLabel;

	@Enumerated(EnumType.STRING)
	private CrawlMode mode;

	private Instant startedAt;

	/** Server-time anchor at crawl start; the next incremental crawl uses it as _since. */
	private String serverTimeAtStart;

	private long durationMs;

	@Enumerated(EnumType.STRING)
	private RunStatus status;

	private int added;
	private int updated;
	private int deleted;
	private long records;

	/** Aggregate resource count for this server after the run was applied. */
	private Integer totalAfter;

	private long bytes;
	private int requests;
	private int pages;

	/** Whether the server supported system _history for deletion detection (SEARCH only). */
	private Boolean historySupported;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "error")
	private String error;

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

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}

	public String getServerKey() {
		return serverKey;
	}

	public void setServerKey(String serverKey) {
		this.serverKey = serverKey;
	}

	public String getServerLabel() {
		return serverLabel;
	}

	public void setServerLabel(String serverLabel) {
		this.serverLabel = serverLabel;
	}

	public CrawlMode getMode() {
		return mode;
	}

	public void setMode(CrawlMode mode) {
		this.mode = mode;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public String getServerTimeAtStart() {
		return serverTimeAtStart;
	}

	public void setServerTimeAtStart(String serverTimeAtStart) {
		this.serverTimeAtStart = serverTimeAtStart;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	public RunStatus getStatus() {
		return status;
	}

	public void setStatus(RunStatus status) {
		this.status = status;
	}

	public int getAdded() {
		return added;
	}

	public void setAdded(int added) {
		this.added = added;
	}

	public int getUpdated() {
		return updated;
	}

	public void setUpdated(int updated) {
		this.updated = updated;
	}

	public int getDeleted() {
		return deleted;
	}

	public void setDeleted(int deleted) {
		this.deleted = deleted;
	}

	public long getRecords() {
		return records;
	}

	public void setRecords(long records) {
		this.records = records;
	}

	public Integer getTotalAfter() {
		return totalAfter;
	}

	public void setTotalAfter(Integer totalAfter) {
		this.totalAfter = totalAfter;
	}

	public long getBytes() {
		return bytes;
	}

	public void setBytes(long bytes) {
		this.bytes = bytes;
	}

	public int getRequests() {
		return requests;
	}

	public void setRequests(int requests) {
		this.requests = requests;
	}

	public int getPages() {
		return pages;
	}

	public void setPages(int pages) {
		this.pages = pages;
	}

	public Boolean getHistorySupported() {
		return historySupported;
	}

	public void setHistorySupported(Boolean historySupported) {
		this.historySupported = historySupported;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}
}
