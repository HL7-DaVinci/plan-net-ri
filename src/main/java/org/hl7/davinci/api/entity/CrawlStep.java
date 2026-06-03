package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One recorded step of a crawl operation, for the play-by-play timeline and live stream. */
@Entity
@Table(
		name = "crawl_step",
		indexes = {@Index(name = "idx_crawl_step_batch", columnList = "batchId,seq")})
public class CrawlStep {

	@Id
	private String id;

	private String batchId;

	private String runId;

	private String serverKey;

	private int seq;

	/** Coarse phase label, e.g. SERVER_TIME, SEARCH, HISTORY, EXPORT, PERSIST, MANIFEST, DONE. */
	private String phase;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "message")
	private String message;

	private String method;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "url")
	private String url;

	private Integer status;

	private Long ms;

	private Long bytes;

	private Integer count;

	private Instant at;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public String getServerKey() {
		return serverKey;
	}

	public void setServerKey(String serverKey) {
		this.serverKey = serverKey;
	}

	public int getSeq() {
		return seq;
	}

	public void setSeq(int seq) {
		this.seq = seq;
	}

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Long getMs() {
		return ms;
	}

	public void setMs(Long ms) {
		this.ms = ms;
	}

	public Long getBytes() {
		return bytes;
	}

	public void setBytes(Long bytes) {
		this.bytes = bytes;
	}

	public Integer getCount() {
		return count;
	}

	public void setCount(Integer count) {
		this.count = count;
	}

	public Instant getAt() {
		return at;
	}

	public void setAt(Instant at) {
		this.at = at;
	}
}
