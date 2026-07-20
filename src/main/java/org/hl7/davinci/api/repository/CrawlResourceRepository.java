package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.entity.CrawlResourceId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CrawlResourceRepository extends JpaRepository<CrawlResource, CrawlResourceId> {

	/** Diff-key projection for the given uids within one (server, type). */
	@Query("select r.id.uid as uid, r.versionId as versionId, r.lastUpdated as lastUpdated "
			+ "from CrawlResource r where r.id.serverId = :serverId and r.id.typeId = :typeId "
			+ "and r.id.uid in :uids")
	List<ResourceVersionView> findVersionViews(
			@Param("serverId") int serverId, @Param("typeId") int typeId, @Param("uids") Collection<String> uids);

	/** One keyset page within (server, type), used to stream the aggregate for export and deletion scans. */
	List<CrawlResource> findByIdServerIdAndIdTypeIdAndIdUidGreaterThanOrderByIdUidAsc(
			int serverId, int typeId, String afterUid, Pageable pageable);

	/** A keyset page of bare uids within (server, type), ordered by uid. */
	@Query("select r.id.uid from CrawlResource r where r.id.serverId = :serverId "
			+ "and r.id.typeId = :typeId and r.id.uid > :afterUid order by r.id.uid asc")
	List<String> findUids(
			@Param("serverId") int serverId,
			@Param("typeId") int typeId,
			@Param("afterUid") String afterUid,
			Pageable pageable);

	long countByIdServerIdAndIdTypeId(int serverId, int typeId);

	long countByIdServerId(int serverId);

	/** A bulk SQL delete (no entity loading) of a whole server's aggregate. Needs an ambient transaction. */
	@Modifying
	@Query("delete from CrawlResource r where r.id.serverId = :serverId")
	int deleteByServerId(@Param("serverId") int serverId);

	/** Spring Data projection: just the fields the diff needs. */
	interface ResourceVersionView {
		String getUid();

		String getVersionId();

		String getLastUpdated();
	}
}
