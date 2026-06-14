package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CrawlResourceRepository extends JpaRepository<CrawlResource, String> {

	List<CrawlResource> findByServerKey(String serverKey);

	List<CrawlResource> findByServerKeyAndResourceType(String serverKey, String resourceType);

	/**
	 * One keyset page ordered by primary key, used to stream the manifest snapshot without
	 * materializing the whole aggregate. Filtering on the primary key alone (no serverKey equality)
	 * forces H2 to range-scan the primary key index, which never sorts; adding a serverKey equality
	 * predicate lets the optimizer pick the serverKey index and re-sort every page. Keys are
	 * {@code serverKey|Type/id}, so the caller bounds one server by its {@code serverKey|} prefix.
	 */
	List<CrawlResource> findByKeyGreaterThanOrderByKeyAsc(String afterKey, Pageable pageable);

	long countByServerKey(String serverKey);

	/** Lightweight projection of the diff keys (version/lastUpdated) without loading bodies. */
	@Query("select r.key as key, r.versionId as versionId, r.lastUpdated as lastUpdated "
			+ "from CrawlResource r where r.serverKey = :serverKey")
	List<ResourceVersionView> findVersionViewByServerKey(@Param("serverKey") String serverKey);

	/** Spring Data projection: just the fields the diff needs. */
	interface ResourceVersionView {
		String getKey();

		String getVersionId();

		String getLastUpdated();
	}
}
