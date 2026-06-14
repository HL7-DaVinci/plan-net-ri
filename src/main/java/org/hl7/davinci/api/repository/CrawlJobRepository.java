package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, String> {

	/** Enabled jobs whose next scheduled run is due. */
	List<CrawlJob> findByEnabledTrueAndNextRunAtLessThanEqual(Instant now);

	List<CrawlJob> findByEnabledTrue();

	/** Jobs still flagged running; after a restart these are stale (no live worker backs them). */
	List<CrawlJob> findByRunningTrue();
}
