package org.hl7.davinci.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link CrawlResource}. Field names ({@code serverId}, {@code typeId},
 * {@code uid}) and column names ({@code server_id}, {@code type_id}, {@code uid}) are chosen so
 * their alphabetical order matches the desired index order: Hibernate orders composite PK
 * columns alphabetically by attribute name, not by declaration order. Do not rename {@code uid}
 * to remoteId/resourceId; either would sort before serverId/typeId and break the PK-prefix
 * access pattern every per-server and per-type query depends on.
 */
@Embeddable
public class CrawlResourceId implements Serializable {

	@Column(name = "server_id", nullable = false)
	private int serverId;

	@Column(name = "type_id", nullable = false)
	private int typeId;

	@Column(name = "uid", nullable = false, length = 128)
	private String uid;

	protected CrawlResourceId() {}

	public CrawlResourceId(int serverId, int typeId, String uid) {
		this.serverId = serverId;
		this.typeId = typeId;
		this.uid = uid;
	}

	public int getServerId() {
		return serverId;
	}

	public int getTypeId() {
		return typeId;
	}

	public String getUid() {
		return uid;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CrawlResourceId that)) {
			return false;
		}
		return serverId == that.serverId && typeId == that.typeId && Objects.equals(uid, that.uid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(serverId, typeId, uid);
	}
}
