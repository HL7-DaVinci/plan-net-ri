package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CrawlCheckpointRepository extends JpaRepository<CrawlCheckpoint, String> {

	List<CrawlCheckpoint> findByJobIdAndServerKey(String jobId, String serverKey);

	boolean existsByJobId(String jobId);

	@Transactional("crawlerTransactionManager")
	void deleteByJobIdAndServerKey(String jobId, String serverKey);

	@Transactional("crawlerTransactionManager")
	void deleteByJobId(String jobId);
}
