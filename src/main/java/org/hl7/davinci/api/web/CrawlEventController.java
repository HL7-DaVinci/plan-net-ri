package org.hl7.davinci.api.web;

import org.hl7.davinci.api.model.CrawlStepResponse;
import org.hl7.davinci.api.service.CrawlEventService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Serves the recorded play-by-play steps and the live SSE stream for a crawl operation. */
@RestController
@RequestMapping("/api")
public class CrawlEventController {

	private final CrawlEventService events;

	public CrawlEventController(CrawlEventService events) {
		this.events = events;
	}

	@GetMapping("/crawl/{batchId}/steps")
	public List<CrawlStepResponse> steps(@PathVariable("batchId") String batchId) {
		return events.steps(batchId);
	}

	@GetMapping(value = "/crawl/{batchId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@PathVariable("batchId") String batchId) {
		return events.subscribe(batchId);
	}
}
