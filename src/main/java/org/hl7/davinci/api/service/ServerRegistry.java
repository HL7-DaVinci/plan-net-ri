package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlServer;
import org.hl7.davinci.api.repository.CrawlServerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only component that touches {@link CrawlServerRepository}; a small in-memory cache sits
 * in front of the crawl_server lookup table so a hot crawl never round-trips the DB for a
 * server it has already resolved.
 */
@Service
public class ServerRegistry {

	private final CrawlServerRepository serverRepo;
	private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

	public ServerRegistry(CrawlServerRepository serverRepo) {
		this.serverRepo = serverRepo;
	}

	/**
	 * Get-or-create the server id for a server key, creating the crawl_server row if needed.
	 * Deliberately not {@code @Transactional}: each repository call must run in its own
	 * transaction (bound to crawlerTransactionManager via the repository's own
	 * transactionManagerRef), so a failed insert cannot mark an enclosing transaction
	 * rollback-only. Wrapping this method in a transaction would poison that transaction the
	 * instant save() throws the constraint violation, making the catch block's re-read run
	 * inside a doomed transaction that fails to commit even on success.
	 */
	public int idFor(String serverKey) {
		Integer cached = cache.get(serverKey);
		if (cached != null) {
			return cached;
		}
		Optional<CrawlServer> existing = serverRepo.findByServerKey(serverKey);
		if (existing.isPresent()) {
			int id = existing.get().getId();
			cache.put(serverKey, id);
			return id;
		}
		try {
			CrawlServer server = new CrawlServer();
			server.setServerKey(serverKey);
			server = serverRepo.save(server);
			cache.put(serverKey, server.getId());
			return server.getId();
		} catch (DataIntegrityViolationException e) {
			// Two parallel crawls resolving the same new server raced the unique constraint;
			// the loser re-reads the winner's row.
			CrawlServer server = serverRepo.findByServerKey(serverKey).orElseThrow(() -> e);
			cache.put(serverKey, server.getId());
			return server.getId();
		}
	}

	/** Read-only lookup; never creates a row. Stats, export, and deletion paths must not create servers. */
	public OptionalInt idIfExists(String serverKey) {
		Integer cached = cache.get(serverKey);
		if (cached != null) {
			return OptionalInt.of(cached);
		}
		Optional<CrawlServer> found = serverRepo.findByServerKey(serverKey);
		if (found.isEmpty()) {
			return OptionalInt.empty();
		}
		int id = found.get().getId();
		cache.put(serverKey, id);
		return OptionalInt.of(id);
	}

	public void evict(String serverKey) {
		cache.remove(serverKey);
	}

	/** Removes the crawl_server row for a server no remaining job targets. */
	@Transactional("crawlerTransactionManager")
	public void deleteServer(String serverKey) {
		serverRepo.deleteByServerKey(serverKey);
		evict(serverKey);
	}
}
