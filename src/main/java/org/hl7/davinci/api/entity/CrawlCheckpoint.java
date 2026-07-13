package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Mid-crawl resume point for one resource type: everything with {@code _lastUpdated} below the
 * watermark has been fetched and persisted for this job and server. Written by the watermark
 * search strategies as their per-type frontier advances, consumed as the type's lower bound when
 * a crawl starts, and deleted when the server's run completes (or the job is deleted). Strategies
 * without a restart-stable cursor (page links, bulk export, history) never write one.
 */
@Entity
@Table(name = "crawl_checkpoint")
public class CrawlCheckpoint {

	/** {@code jobId|serverKey|resourceType}. */
	@Id
	@Column(name = "checkpoint_key", length = 512)
	private String key;

	private String jobId;

	@Column(length = 512)
	private String serverKey;

	private String resourceType;

	/** Inclusive resume point: the next crawl re-queries {@code ge} this instant. */
	private String watermark;

	private Instant updatedAt;

	public static String key(String jobId, String serverKey, String resourceType) {
		return jobId + "|" + serverKey + "|" + resourceType;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getServerKey() {
		return serverKey;
	}

	public void setServerKey(String serverKey) {
		this.serverKey = serverKey;
	}

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public String getWatermark() {
		return watermark;
	}

	public void setWatermark(String watermark) {
		this.watermark = watermark;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
