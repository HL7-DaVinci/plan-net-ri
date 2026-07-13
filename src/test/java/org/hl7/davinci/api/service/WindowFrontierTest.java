package org.hl7.davinci.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class WindowFrontierTest {

	private static FhirCrawlClient.WindowFrontier threeWindows() {
		return new FhirCrawlClient.WindowFrontier(List.of(
				new FhirCrawlClient.Window("t0", "t1"),
				new FhirCrawlClient.Window("t1", "t2"),
				new FhirCrawlClient.Window("t2", null)));
	}

	@Test
	void frontierFollowsTheLowestIncompleteWindow() {
		FhirCrawlClient.WindowFrontier frontier = threeWindows();

		assertEquals("w0-page", frontier.advance(0, "w0-page", false), "window 0's watermark is the frontier");
		assertNull(frontier.advance(2, "w2-page", false), "a higher window's progress is not claimable");
		assertEquals("t1", frontier.advance(0, null, true), "window 0 done: frontier jumps to window 1's start");
		assertEquals("w2-page", frontier.advance(1, null, true), "window 1 done: window 2's earlier progress counts");
		assertNull(frontier.advance(2, null, true), "all windows done: the last watermark was already reported");
	}

	@Test
	void anIncompleteLowWindowPinsTheFrontier() {
		FhirCrawlClient.WindowFrontier frontier = threeWindows();

		assertEquals("t0", frontier.advance(1, "w1-page", true), "only window 0's untouched start is claimable");
		assertNull(frontier.advance(2, "w2-page", true), "higher windows completing must not move the frontier");
	}

	@Test
	void unchangedFrontierIsReportedOnlyOnce() {
		FhirCrawlClient.WindowFrontier frontier = threeWindows();

		assertEquals("w0-page", frontier.advance(0, "w0-page", false));
		assertNull(frontier.advance(0, "w0-page", false), "a cluster-follow page repeats the watermark silently");
		assertEquals("w0-page2", frontier.advance(0, "w0-page2", false));
	}
}
