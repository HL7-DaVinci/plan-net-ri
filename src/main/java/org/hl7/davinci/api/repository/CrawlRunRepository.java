package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.RunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, String> {

	List<CrawlRun> findByJobIdOrderByStartedAtDesc(String jobId);

	/** Distinct batch ids across all of a job's runs; used to cascade-delete the job's steps. */
	@Query("select distinct r.batchId from CrawlRun r where r.jobId = :jobId")
	List<String> findBatchIdsByJobId(@Param("jobId") String jobId);

	void deleteByJobId(String jobId);

	Page<CrawlRun> findByJobIdOrderByStartedAtDesc(String jobId, Pageable pageable);

	List<CrawlRun> findByBatchIdOrderByStartedAtDesc(String batchId);

	/**
	 * The latest completed run for a given job and server. Its {@code serverTimeAtStart}
	 * is the incremental {@code _since} anchor for the next SEARCH crawl.
	 */
	Optional<CrawlRun> findTop1ByJobIdAndServerKeyAndStatusOrderByStartedAtDesc(
			String jobId, String serverKey, RunStatus status);
}
