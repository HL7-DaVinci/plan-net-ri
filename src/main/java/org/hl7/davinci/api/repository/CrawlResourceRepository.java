package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CrawlResourceRepository extends JpaRepository<CrawlResource, String> {

	/**
	 * One keyset page ordered by primary key, used to stream the manifest snapshot without
	 * materializing the whole aggregate. Filtering on the primary key alone (no serverKey equality)
	 * forces H2 to range-scan the primary key index, which never sorts; adding a serverKey equality
	 * predicate lets the optimizer pick the serverKey index and re-sort every page. Keys are
	 * {@code serverKey|Type/id}, so the caller bounds one server by its {@code serverKey|} prefix.
	 */
	List<CrawlResource> findByKeyGreaterThanOrderByKeyAsc(String afterKey, Pageable pageable);

	/**
	 * One server's rows occupy an exclusive primary-key range: keys are {@code serverKey|Type/id},
	 * '}' is the character after '|', and server keys never contain '|'.
	 */
	@Query("select count(r) from CrawlResource r where r.key > :from and r.key < :to")
	long countByKeyRange(@Param("from") String from, @Param("to") String to);

	default long countByServerKey(String serverKey) {
		return countByKeyRange(serverKey + "|", serverKey + "}");
	}

	@Modifying
	@Query("delete from CrawlResource r where r.key > :from and r.key < :to")
	int deleteByKeyRange(@Param("from") String from, @Param("to") String to);

	/** A bulk SQL delete (no entity loading) of a whole server's aggregate. Needs an ambient transaction. */
	default int deleteByServerKey(String serverKey) {
		return deleteByKeyRange(serverKey + "|", serverKey + "}");
	}

	/**
	 * One (server, type) count as a primary-key range: keys are {@code serverKey|Type/id} and
	 * '0' is the character after '/', so the range covers exactly that type's rows. This keeps
	 * overall stats on the PK index (an index-only range count) instead of a full-table scan
	 * over an unindexed resourceType column.
	 */
	default long countByServerKeyAndType(String serverKey, String resourceType) {
		String prefix = serverKey + "|" + resourceType + "/";
		return countByKeyRange(prefix, serverKey + "|" + resourceType + "0");
	}

	/** Diff-key projection for the given keys. */
	@Query("select r.key as key, r.versionId as versionId, r.lastUpdated as lastUpdated "
			+ "from CrawlResource r where r.key in :keys")
	List<ResourceVersionView> findVersionViewByKeys(@Param("keys") Collection<String> keys);

	/** A keyset page of bare primary keys, ordered by key; the caller bounds one server by its {@code serverKey|} prefix. */
	@Query("select r.key from CrawlResource r where r.key > :afterKey order by r.key asc")
	List<String> findKeysByKeyGreaterThanOrderByKeyAsc(@Param("afterKey") String afterKey, Pageable pageable);

	/** Spring Data projection: just the fields the diff needs. */
	interface ResourceVersionView {
		String getKey();

		String getVersionId();

		String getLastUpdated();
	}
}
