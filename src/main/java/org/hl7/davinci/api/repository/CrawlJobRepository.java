package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlJob;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, String> {

	/** Enabled jobs whose next scheduled run is due. */
	List<CrawlJob> findByEnabledTrueAndNextRunAtLessThanEqual(Instant now);

	List<CrawlJob> findByEnabledTrue();
}
