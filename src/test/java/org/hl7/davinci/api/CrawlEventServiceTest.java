package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hl7.davinci.api.entity.CrawlStep;
import org.hl7.davinci.api.model.CrawlStepResponse;
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

	@Test
	void progressMarkersForDifferentTracksAreBothKeptForReplay() {
		List<CrawlStep> saved = new ArrayList<>();
		CrawlEventService events = new CrawlEventService(stepRepo(saved));
		events.start("batch-1");

		events.publish(
				"batch-1",
				null,
				null,
				1,
				StepEvent.progress("SEARCH", "Fetching Organization...").withTrack("Organization"));
		events.publish(
				"batch-1", null, null, 2, StepEvent.progress("SEARCH", "Fetching Location...").withTrack("Location"));

		Map<String, CrawlStepResponse> tracks = lastProgress(events, "batch-1");
		assertEquals(2, tracks.size(), "a late subscriber replays one marker per active track");
		assertEquals("Organization", tracks.get("Organization").track());
		assertEquals("Location", tracks.get("Location").track());

		// subscribe() replays every entry of the track map; it must not throw with two active tracks.
		events.subscribe("batch-1");
	}

	@Test
	void persistedStepWithATrackClearsOnlyThatTrack() {
		List<CrawlStep> saved = new ArrayList<>();
		CrawlEventService events = new CrawlEventService(stepRepo(saved));
		events.start("batch-1");

		events.publish(
				"batch-1",
				null,
				null,
				1,
				StepEvent.progress("SEARCH", "Fetching Organization...").withTrack("Organization"));
		events.publish(
				"batch-1", null, null, 2, StepEvent.progress("SEARCH", "Fetching Location...").withTrack("Location"));

		events.publish(
				"batch-1", null, null, 3, StepEvent.info("SEARCH", "Organization done").withTrack("Organization"));

		Map<String, CrawlStepResponse> tracks = lastProgress(events, "batch-1");
		assertEquals(1, tracks.size());
		assertTrue(tracks.containsKey("Location"), "the untouched track's marker must survive");
	}

	@Test
	void settledProgressMarkerClearsItsTrackInsteadOfLingering() {
		List<CrawlStep> saved = new ArrayList<>();
		CrawlEventService events = new CrawlEventService(stepRepo(saved));
		events.start("batch-1");

		events.publish(
				"batch-1",
				null,
				null,
				1,
				StepEvent.progress("SEARCH", "Searching Location window...").withTrack("Location [1/7]"));
		events.publish(
				"batch-1",
				null,
				null,
				2,
				StepEvent.progress("SEARCH", "Searching Location window...").withTrack("Location [2/7]"));

		// A finished window's resolution is broadcast-only (asProgress) but carries its HTTP
		// result; it must end the track rather than replay to late subscribers forever.
		events.publish(
				"batch-1",
				null,
				null,
				3,
				StepEvent.request("SEARCH", "Searched Location window (288 pages)", "GET", "http://x", 200, 5L, 1L, 10)
						.withTrack("Location [1/7]")
						.asProgress());

		assertTrue(saved.isEmpty(), "a settled marker is still never persisted");
		Map<String, CrawlStepResponse> tracks = lastProgress(events, "batch-1");
		assertEquals(1, tracks.size());
		assertTrue(tracks.containsKey("Location [2/7]"), "only the still-running window's marker survives");
	}

	@Test
	void persistedStepWithNullTrackClearsEveryTrack() {
		List<CrawlStep> saved = new ArrayList<>();
		CrawlEventService events = new CrawlEventService(stepRepo(saved));
		events.start("batch-1");

		events.publish(
				"batch-1",
				null,
				null,
				1,
				StepEvent.progress("SEARCH", "Fetching Organization...").withTrack("Organization"));
		events.publish(
				"batch-1", null, null, 2, StepEvent.progress("SEARCH", "Fetching Location...").withTrack("Location"));

		events.publish("batch-1", null, null, 3, StepEvent.info("DONE", "Crawl complete"));

		assertNull(lastProgress(events, "batch-1"), "a job-level (untracked) step clears every track's marker");
	}

	@Test
	void responsesCarryTheTrack() {
		List<CrawlStep> saved = new ArrayList<>();
		CrawlEventService events = new CrawlEventService(stepRepo(saved));
		events.start("batch-1");

		events.publish(
				"batch-1", null, "server-1", 1, StepEvent.info("SEARCH", "Organization done").withTrack("Organization"));

		assertEquals("Organization", saved.get(0).getTrack(), "the persisted entity records the track");

		List<CrawlStepResponse> steps = events.steps("batch-1");
		assertEquals("Organization", steps.get(0).track(), "the DTO carries the track through");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, CrawlStepResponse> lastProgress(CrawlEventService events, String batchId) {
		try {
			Field field = CrawlEventService.class.getDeclaredField("lastProgress");
			field.setAccessible(true);
			Map<String, Map<String, CrawlStepResponse>> lastProgress =
					(Map<String, Map<String, CrawlStepResponse>>) field.get(events);
			return lastProgress.get(batchId);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
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
					case "findByBatchIdOrderBySeqAsc" -> saved;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
