package org.hl7.davinci.api.service;

import org.hl7.davinci.api.entity.CrawlStep;
import org.hl7.davinci.api.model.CrawlStepResponse;
import org.hl7.davinci.api.repository.CrawlStepRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records crawl steps (for the play-by-play timeline) and broadcasts them to live SSE
 * subscribers. Steps for one crawl operation are keyed by {@code batchId}.
 */
@Service
public class CrawlEventService {

	/** Heartbeat interval; keeps idle SSE connections (and any reverse proxy) warm during quiet phases. */
	private static final long HEARTBEAT_MS = 15_000L;

	private final CrawlStepRepository stepRepo;
	private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
	private final Set<String> activeBatches = ConcurrentHashMap.newKeySet();

	/**
	 * The latest transient progress marker per active batch, keyed by track (empty string for
	 * job-level, untracked steps), replayed to late subscribers. Both map levels are
	 * ConcurrentHashMap so parallel workers on different tracks never lose an update to each
	 * other, and every structural change goes through compute() on the outer map so a track
	 * removal that empties the inner map can't race a sibling's insert into that same map.
	 */
	private final Map<String, Map<String, CrawlStepResponse>> lastProgress = new ConcurrentHashMap<>();

	public CrawlEventService(CrawlStepRepository stepRepo) {
		this.stepRepo = stepRepo;
	}

	/** Mark a crawl operation as in-flight so subscribers stream live rather than replay-and-close. */
	public void start(String batchId) {
		activeBatches.add(batchId);
	}

	/**
	 * Persist a step and push it to any live subscribers. Transient progress events are
	 * broadcast only, never persisted, so the recorded timeline stays one row per completed step.
	 */
	public void publish(String batchId, String runId, String serverKey, int seq, StepEvent event) {
		String trackKey = event.track() == null ? "" : event.track();

		if (event.progress()) {
			CrawlStepResponse dto = new CrawlStepResponse(
					seq,
					event.phase(),
					event.message(),
					event.method(),
					event.url(),
					event.status(),
					event.ms(),
					event.bytes(),
					event.count(),
					null,
					serverKey,
					event.track(),
					String.valueOf(Instant.now()));
			// A marker carrying its HTTP result is a settled resolution: the track's work just
			// finished, so its line comes down and is not replayed to late subscribers. Only
			// in-flight markers (no status yet) stay live.
			if (event.status() != null) {
				clearProgress(batchId, trackKey);
			} else {
				upsertProgress(batchId, trackKey, dto);
			}
			broadcast(batchId, "progress", dto);
			return;
		}

		CrawlStep step = new CrawlStep();
		step.setId(UUID.randomUUID().toString());
		step.setBatchId(batchId);
		step.setRunId(runId);
		step.setServerKey(serverKey);
		step.setSeq(seq);
		step.setPhase(event.phase());
		step.setMessage(StepEvent.clip(event.message(), StepEvent.MAX_TEXT_CHARS));
		step.setMethod(event.method());
		step.setUrl(StepEvent.clip(event.url(), StepEvent.MAX_TEXT_CHARS));
		step.setStatus(event.status());
		step.setMs(event.ms());
		step.setBytes(event.bytes());
		step.setCount(event.count());
		step.setErrorBody(event.errorBody());
		step.setTrack(event.track());
		step.setAt(Instant.now());
		stepRepo.save(step);

		// A persisted step means the in-progress operation it belongs to finished: a null track
		// is a job-level step and clears every track's marker, a set track clears only that one.
		if (event.track() == null) {
			lastProgress.remove(batchId);
		} else {
			clearProgress(batchId, trackKey);
		}
		broadcast(batchId, "step", toDto(step));
	}

	private void upsertProgress(String batchId, String trackKey, CrawlStepResponse dto) {
		lastProgress.compute(batchId, (k, tracks) -> {
			Map<String, CrawlStepResponse> map = tracks != null ? tracks : new ConcurrentHashMap<>();
			map.put(trackKey, dto);
			return map;
		});
	}

	private void clearProgress(String batchId, String trackKey) {
		lastProgress.compute(batchId, (k, tracks) -> {
			if (tracks == null) {
				return null;
			}
			tracks.remove(trackKey);
			return tracks.isEmpty() ? null : tracks;
		});
	}

	private void broadcast(String batchId, String name, CrawlStepResponse dto) {
		List<SseEmitter> subscribers = emitters.get(batchId);
		if (subscribers != null) {
			for (SseEmitter emitter : subscribers) {
				trySend(batchId, emitter, name, dto);
			}
		}
	}

	/** Close the operation: notify and complete any live subscribers. */
	public void complete(String batchId) {
		activeBatches.remove(batchId);
		lastProgress.remove(batchId);
		List<SseEmitter> subscribers = emitters.remove(batchId);
		if (subscribers != null) {
			for (SseEmitter emitter : subscribers) {
				sendComplete(emitter);
				emitter.complete();
			}
		}
	}

	/** The recorded steps for a batch, oldest first. */
	public List<CrawlStepResponse> steps(String batchId) {
		return stepRepo.findByBatchIdOrderBySeqAsc(batchId).stream()
				.map(this::toDto)
				.toList();
	}

	/** Subscribe to a batch: replay recorded steps, then stream live ones (or close if finished). */
	public SseEmitter subscribe(String batchId) {
		// 0 = no servlet async idle timeout; the scheduled heartbeat keeps the connection warm instead.
		SseEmitter emitter = new SseEmitter(0L);
		try {
			for (CrawlStep step : stepRepo.findByBatchIdOrderBySeqAsc(batchId)) {
				emitter.send(SseEmitter.event().name("step").data(toDto(step)));
			}
			// Show a late subscriber what is currently executing (e.g. a slow page fetch), one
			// line per active track, in a stable order.
			Map<String, CrawlStepResponse> tracks = lastProgress.get(batchId);
			if (tracks != null) {
				for (String trackKey : new TreeSet<>(tracks.keySet())) {
					emitter.send(SseEmitter.event().name("progress").data(tracks.get(trackKey)));
				}
			}
		} catch (Exception e) {
			emitter.completeWithError(e);
			return emitter;
		}

		if (activeBatches.contains(batchId)) {
			emitters.computeIfAbsent(batchId, k -> new CopyOnWriteArrayList<>()).add(emitter);
			emitter.onCompletion(() -> remove(batchId, emitter));
			emitter.onTimeout(() -> {
				remove(batchId, emitter);
				emitter.complete();
			});
			// complete() clears activeBatches before draining emitters, so a re-check here closes
			// the window where complete() ran between the check above and this registration.
			if (!activeBatches.contains(batchId)) {
				remove(batchId, emitter);
				sendComplete(emitter);
				emitter.complete();
			}
		} else {
			sendComplete(emitter);
			emitter.complete();
		}
		return emitter;
	}

	private void remove(String batchId, SseEmitter emitter) {
		List<SseEmitter> subscribers = emitters.get(batchId);
		if (subscribers != null) {
			subscribers.remove(emitter);
		}
	}

	/**
	 * Send a no-op comment to every live connection so an idle stream (a long fetch/persist with no
	 * persisted step) is not cut by the servlet container or a reverse proxy. A failed send means the
	 * client is gone, so drop it.
	 */
	@Scheduled(fixedRate = HEARTBEAT_MS)
	void heartbeat() {
		emitters.forEach((batchId, subscribers) -> {
			for (SseEmitter emitter : subscribers) {
				try {
					synchronized (emitter) {
						emitter.send(SseEmitter.event().comment("hb"));
					}
				} catch (Exception e) {
					remove(batchId, emitter);
				}
			}
		});
	}

	// Sends are serialized per emitter because the heartbeat thread can write at the same time as the
	// crawl worker; interleaving two writes would corrupt the SSE framing.
	private void trySend(String batchId, SseEmitter emitter, String name, CrawlStepResponse dto) {
		try {
			synchronized (emitter) {
				emitter.send(SseEmitter.event().name(name).data(dto));
			}
		} catch (Exception e) {
			remove(batchId, emitter);
		}
	}

	private void sendComplete(SseEmitter emitter) {
		try {
			synchronized (emitter) {
				emitter.send(SseEmitter.event().name("complete").data("done"));
			}
		} catch (Exception ignored) {
			// client gone; nothing to do
		}
	}

	private CrawlStepResponse toDto(CrawlStep s) {
		return new CrawlStepResponse(
				s.getSeq(),
				s.getPhase(),
				s.getMessage(),
				s.getMethod(),
				s.getUrl(),
				s.getStatus(),
				s.getMs(),
				s.getBytes(),
				s.getCount(),
				s.getErrorBody(),
				s.getServerKey(),
				s.getTrack(),
				String.valueOf(s.getAt()));
	}
}
