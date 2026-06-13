package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CrawlResourceRepository extends JpaRepository<CrawlResource, String> {

	List<CrawlResource> findByServerKey(String serverKey);

	List<CrawlResource> findByServerKeyAndResourceType(String serverKey, String resourceType);

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
