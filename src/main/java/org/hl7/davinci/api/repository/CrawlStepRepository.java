package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlStep;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlStepRepository extends JpaRepository<CrawlStep, String> {

	List<CrawlStep> findByBatchIdOrderBySeqAsc(String batchId);

	void deleteByBatchIdIn(Collection<String> batchIds);
}
