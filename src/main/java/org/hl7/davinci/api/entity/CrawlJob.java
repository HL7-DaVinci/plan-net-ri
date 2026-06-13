package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A configured crawl job: servers, strategy, and schedule. */
@Entity
@Table(name = "crawl_job")
public class CrawlJob {

	@Id
	private String id;

	private String name;

	/** JSON array of {serverKey, serverLabel, url}. */
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "servers")
	private String servers;

	@Enumerated(EnumType.STRING)
	private CrawlStrategy strategy;

	private String cronExpression;

	private boolean enabled;

	/** Single-flight guard: true while a crawl for this job is in flight. */
	private boolean running;

	private Instant lastRunAt;

	private Instant nextRunAt;

	private Instant createdAt;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getServers() {
		return servers;
	}

	public void setServers(String servers) {
		this.servers = servers;
	}

	public CrawlStrategy getStrategy() {
		return strategy;
	}

	public void setStrategy(CrawlStrategy strategy) {
		this.strategy = strategy;
	}

	public String getCronExpression() {
		return cronExpression;
	}

	public void setCronExpression(String cronExpression) {
		this.cronExpression = cronExpression;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isRunning() {
		return running;
	}

	public void setRunning(boolean running) {
		this.running = running;
	}

	public Instant getLastRunAt() {
		return lastRunAt;
	}

	public void setLastRunAt(Instant lastRunAt) {
		this.lastRunAt = lastRunAt;
	}

	public Instant getNextRunAt() {
		return nextRunAt;
	}

	public void setNextRunAt(Instant nextRunAt) {
		this.nextRunAt = nextRunAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
