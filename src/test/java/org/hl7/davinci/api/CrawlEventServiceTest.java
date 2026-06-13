package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.hl7.davinci.api.entity.CrawlStep;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import org.hl7.davinci.api.service.CrawlEventService;
import org.hl7.davinci.api.service.StepEvent;
import org.junit.jupiter.api.Test;

class CrawlEventServiceTest {

	@Test
	void progressEventsAreBroadcastOnlyAndNeverPersisted() {
		List<CrawlStep> saved = new ArrayList<>();
		CrawlEventService events = new CrawlEventService(stepRepo(saved));
		events.start("batch-1");

		events.publish("batch-1", null, null, 1, StepEvent.progress("SEARCH", "Searching all Organization..."));

		assertTrue(saved.isEmpty(), "progress markers must not be written to the timeline");

		events.publish("batch-1", null, null, 2, StepEvent.info("DONE", "Crawl complete"));

		assertEquals(1, saved.size(), "real steps are persisted as before");
		assertEquals("DONE", saved.get(0).getPhase());
	}

	private static CrawlStepRepository stepRepo(List<CrawlStep> saved) {
		return (CrawlStepRepository) Proxy.newProxyInstance(
				CrawlStepRepository.class.getClassLoader(),
				new Class<?>[] {CrawlStepRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "save" -> {
						saved.add((CrawlStep) args[0]);
						yield args[0];
					}
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
