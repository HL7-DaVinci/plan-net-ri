package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Current aggregated state of one resource for one server; the source for diffing and NDJSON. */
@Entity
@Table(
		name = "crawl_resource",
		indexes = {
			@Index(name = "idx_crawl_resource_server", columnList = "serverKey"),
			@Index(name = "idx_crawl_resource_server_type", columnList = "serverKey,resourceType")
		})
public class CrawlResource {

	/** {@code serverKey|resourceType/id}. */
	@Id
	@Column(name = "resource_key", length = 512)
	private String key;

	private String serverKey;

	private String serverLabel;

	private String resourceType;

	private String resId;

	private String versionId;

	private String lastUpdated;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "resource_json")
	private String resourceJson;

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
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

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public String getResId() {
		return resId;
	}

	public void setResId(String resId) {
		this.resId = resId;
	}

	public String getVersionId() {
		return versionId;
	}

	public void setVersionId(String versionId) {
		this.versionId = versionId;
	}

	public String getLastUpdated() {
		return lastUpdated;
	}

	public void setLastUpdated(String lastUpdated) {
		this.lastUpdated = lastUpdated;
	}

	public String getResourceJson() {
		return resourceJson;
	}

	public void setResourceJson(String resourceJson) {
		this.resourceJson = resourceJson;
	}
}
