package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CrawlStepRepository extends JpaRepository<CrawlStep, String> {

	List<CrawlStep> findByBatchIdOrderBySeqAsc(String batchId);

	/** The last persisted step of a batch; marks how far a crashed run actually got. */
	Optional<CrawlStep> findTop1ByBatchIdOrderBySeqDesc(String batchId);

	void deleteByBatchIdIn(Collection<String> batchIds);
}
