package org.hl7.davinci.publish.feed;

import static org.hl7.davinci.publish.feed.ChangeFeedReader.selectWinners;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeFeedReaderTest {

	@Test
	void higherVersionWinsEvenWithAnEarlierTimestamp() {
		ChangeEntry v2 = new ChangeEntry("Organization", "a", 2, false, 2_000L);
		ChangeEntry v3 = new ChangeEntry("Organization", "a", 3, false, 1_000L);

		Map<String, ChangeEntry> winners = selectWinners(List.of(v2, v3));

		assertEquals(v3, winners.get("a"));
	}

	@Test
	void deleteStubWithHigherVersionBeatsALiveEntry() {
		ChangeEntry live = new ChangeEntry("Organization", "a", 2, false, 1_000L);
		ChangeEntry deleted = new ChangeEntry("Organization", "a", 3, true, 2_000L);

		Map<String, ChangeEntry> winners = selectWinners(List.of(live, deleted));

		assertTrue(winners.get("a").deleted());
		assertEquals(3, winners.get("a").versionId());
	}

	@Test
	void liveReviveWithHigherVersionBeatsADeleteStub() {
		ChangeEntry deleted = new ChangeEntry("Organization", "a", 2, true, 1_000L);
		ChangeEntry revived = new ChangeEntry("Organization", "a", 3, false, 2_000L);

		Map<String, ChangeEntry> winners = selectWinners(List.of(deleted, revived));

		assertTrue(!winners.get("a").deleted());
		assertEquals(3, winners.get("a").versionId());
	}

	@Test
	void duplicateIdenticalEntriesCollapse() {
		ChangeEntry entry = new ChangeEntry("Organization", "a", 2, false, 1_000L);

		Map<String, ChangeEntry> winners = selectWinners(List.of(entry, entry));

		assertEquals(1, winners.size());
		assertEquals(entry, winners.get("a"));
	}

	@Test
	void emptyInputYieldsEmptyMap() {
		Map<String, ChangeEntry> winners = selectWinners(List.of());

		assertTrue(winners.isEmpty());
	}
}
