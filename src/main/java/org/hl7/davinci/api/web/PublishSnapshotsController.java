package org.hl7.davinci.api.web;

import org.hl7.davinci.publish.PublishService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lists the retained $bulk-publish snapshots for the crawler UI's demo page. Reflects what is on
 * disk right now, so pruned snapshots drop out of the response.
 */
@RestController
@RequestMapping("/api/publish")
public class PublishSnapshotsController {

	private final PublishService publishService;

	public PublishSnapshotsController(PublishService publishService) {
		this.publishService = publishService;
	}

	@GetMapping("/snapshots")
	public List<PublishService.SnapshotListing> snapshots() {
		return publishService.listSnapshots();
	}
}
