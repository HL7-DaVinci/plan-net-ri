package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.CrawlServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface CrawlServerRepository extends JpaRepository<CrawlServer, Integer> {

	Optional<CrawlServer> findByServerKey(String serverKey);

	@Transactional("crawlerTransactionManager")
	void deleteByServerKey(String serverKey);
}
