package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CrawlStepRepository extends JpaRepository<CrawlStep, String> {

	List<CrawlStep> findByBatchIdOrderBySeqAsc(String batchId);

	void deleteByBatchIdIn(Collection<String> batchIds);
}
