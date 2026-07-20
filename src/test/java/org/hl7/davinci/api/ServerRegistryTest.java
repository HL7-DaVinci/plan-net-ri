package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.davinci.api.entity.CrawlServer;
import org.hl7.davinci.api.repository.CrawlServerRepository;
import org.hl7.davinci.api.service.ServerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ServerRegistryTest {

	@Test
	void idForIsIdempotentAndCachesAfterTheFirstResolve() {
		AtomicInteger findCalls = new AtomicInteger();
		AtomicInteger saveCalls = new AtomicInteger();
		CrawlServerRepository repo = (CrawlServerRepository) Proxy.newProxyInstance(
				CrawlServerRepository.class.getClassLoader(),
				new Class<?>[] {CrawlServerRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByServerKey" -> {
						findCalls.incrementAndGet();
						yield Optional.empty();
					}
					case "save" -> {
						saveCalls.incrementAndGet();
						CrawlServer saved = (CrawlServer) args[0];
						saved.setId(7);
						yield saved;
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
		ServerRegistry registry = new ServerRegistry(repo);

		int first = registry.idFor("http://a.example/fhir");
		int second = registry.idFor("http://a.example/fhir");

		assertEquals(7, first);
		assertEquals(7, second);
		assertEquals(1, saveCalls.get(), "only the first call should insert a row");
		assertEquals(1, findCalls.get(), "the second call must be served from the in-memory cache");
	}

	@Test
	void idForReReadsTheWinnerWhenTheInsertLosesTheUniqueConstraintRace() {
		AtomicInteger findCalls = new AtomicInteger();
		CrawlServer winner = new CrawlServer();
		winner.setId(3);
		winner.setServerKey("http://a.example/fhir");
		CrawlServerRepository repo = (CrawlServerRepository) Proxy.newProxyInstance(
				CrawlServerRepository.class.getClassLoader(),
				new Class<?>[] {CrawlServerRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByServerKey" -> {
						int call = findCalls.incrementAndGet();
						// The first read (in idFor) misses; a concurrent crawl's insert wins the race, so
						// the re-read after the failed insert finds the winner's row.
						yield call == 1 ? Optional.empty() : Optional.of(winner);
					}
					case "save" -> throw new DataIntegrityViolationException("unique constraint violated");
					default -> throw new UnsupportedOperationException(method.getName());
				});
		ServerRegistry registry = new ServerRegistry(repo);

		int id = registry.idFor("http://a.example/fhir");

		assertEquals(3, id, "the loser of the race must re-read the winner's row");
	}
}
