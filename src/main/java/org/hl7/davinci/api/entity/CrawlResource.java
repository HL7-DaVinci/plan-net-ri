package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Current aggregated state of one resource for one server; the source for diffing and NDJSON.
 * The key embeds the server and identity, so every per-server access is a primary-key range scan
 * over the {@code serverKey|} prefix; no secondary columns or indexes duplicate it.
 */
@Entity
@Table(name = "crawl_resource")
public class CrawlResource implements Persistable<String> {

	/** {@code serverKey|resourceType/id}. */
	@Id
	@Column(name = "resource_key", length = 512)
	private String key;

	/**
	 * Assigned-id entities are otherwise treated as detached, forcing a SELECT before every write
	 * (saveAll -> merge). The crawl diff already knows which rows are new, so it sets this flag to
	 * route added rows through persist() (a plain INSERT, no SELECT).
	 */
	@Transient
	private boolean isNew = true;

	private String resourceType;

	private String versionId;

	private String lastUpdated;

	/**
	 * Length.LONG32 forces a BINARY LARGE OBJECT column; without an explicit length,
	 * Hibernate defaults LONGVARBINARY to Length.LONG (32600 bytes), which large gzipped
	 * resource bodies overflow.
	 */
	@JdbcTypeCode(SqlTypes.LONGVARBINARY)
	@Column(name = "resource_json", length = Length.LONG32)
	private byte[] resourceJson;

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	@Override
	public String getId() {
		return key;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	/** Mark a row as a new insert (true) or an existing-row update (false) before saving. */
	public void setNew(boolean isNew) {
		this.isNew = isNew;
	}

	@PrePersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
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

	public byte[] getResourceJson() {
		return resourceJson;
	}

	public void setResourceJson(byte[] resourceJson) {
		this.resourceJson = resourceJson;
	}
}
