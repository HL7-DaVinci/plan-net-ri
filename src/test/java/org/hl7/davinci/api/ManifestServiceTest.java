package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.CrawlRun;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.repository.CrawlRunRepository;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.api.service.ManifestService;
import org.hl7.davinci.api.service.NdjsonExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ManifestServiceTest {

	@Test
	void deletesEveryManifestRowAndSnapshotDirectoryForTheJob(@TempDir Path tmp) throws Exception {
		List<ManifestRecord> store = new ArrayList<>();
		Path dirA = snapshotDir(tmp, "a");
		Path dirB = snapshotDir(tmp, "b");
		Path dirOther = snapshotDir(tmp, "other");
		store.add(manifest("m-a", "job-1", dirA));
		store.add(manifest("m-b", "job-1", dirB));
		store.add(manifest("m-other", "job-2", dirOther));

		ManifestService service = new ManifestService(null, fakeRepo(store), null, null);

		int deleted = service.deleteManifestsForJob("job-1");

		assertEquals(2, deleted);
		assertFalse(Files.exists(dirA), "job-1 snapshot A should be removed from disk");
		assertFalse(Files.exists(dirB), "job-1 snapshot B should be removed from disk");
		assertTrue(Files.exists(dirOther), "another job's snapshot must remain");
		assertEquals(1, store.size());
		assertEquals("m-other", store.get(0).getId());
	}

	@Test
	void returnsZeroWhenJobHasNoManifests() {
		ManifestService service = new ManifestService(null, fakeRepo(new ArrayList<>()), null, null);
		assertEquals(0, service.deleteManifestsForJob("job-1"));
	}

	@Test
	void withinATransactionTheRowGoesButTheDirectorySurvivesUntilCommit(@TempDir Path tmp) throws Exception {
		List<ManifestRecord> store = new ArrayList<>();
		Path dir = snapshotDir(tmp, "a");
		store.add(manifest("m-a", "job-1", dir));
		ManifestService service = new ManifestService(null, fakeRepo(store), null, null);

		TransactionSynchronizationManager.initSynchronization();
		try {
			int deleted = service.deleteManifestsForJob("job-1");

			assertEquals(1, deleted);
			assertEquals(0, store.size(), "row is removed inside the transaction");
			assertTrue(Files.exists(dir), "directory must survive until the transaction commits");

			for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
				sync.afterCommit();
			}
			assertFalse(Files.exists(dir), "directory is removed once the transaction commits");
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void renderCountsLinesForEachSnapshotFile(@TempDir Path tmp) throws Exception {
		Path dir = Files.createDirectories(tmp.resolve("snap"));
		Files.writeString(dir.resolve("Practitioner.ndjson"), "{}\n{}\n{}\n");
		ManifestService service = new ManifestService(null, null, null, null);

		var output = service.render(manifest("m", "job", dir), "http://x").output();

		assertEquals(1, output.size());
		assertEquals(3, output.get(0).count(), "render counts the resource lines per type");
	}

	@Test
	void renderPrefersRecordedCountsOverCountingLines(@TempDir Path tmp) throws Exception {
		Path dir = Files.createDirectories(tmp.resolve("snap"));
		Files.writeString(dir.resolve("Practitioner.ndjson"), "{}\n{}\n");
		Files.writeString(dir.resolve("counts.json"), "{\"Practitioner\":5}");
		ManifestService service = new ManifestService(null, null, null, null);

		var output = service.render(manifest("m", "job", dir), "http://x").output();

		assertEquals(5, output.get(0).count(), "the sidecar count wins; the file is never re-counted");
	}

	@Test
	void renderRecordsCountsForALegacySnapshotSoItIsOnlyCountedOnce(@TempDir Path tmp) throws Exception {
		Path dir = Files.createDirectories(tmp.resolve("snap"));
		Files.writeString(dir.resolve("Practitioner.ndjson"), "{}\n{}\n");
		ManifestService service = new ManifestService(null, null, null, null);

		var output = service.render(manifest("m", "job", dir), "http://x").output();

		assertEquals(2, output.get(0).count());
		assertEquals(
				"{\"Practitioner\":2}",
				Files.readString(dir.resolve("counts.json")),
				"a snapshot written before counts were recorded self-heals on first render");
	}

	@Test
	void regenerateRebuildsTheFilesFromTheAggregateAndUpdatesTheRow(@TempDir Path tmp) throws Exception {
		ApiProperties props = new ApiProperties();
		props.setStoragePath(tmp.toString());
		// The manifest row survived but its files did not (e.g. non-persistent storage).
		ManifestRecord record = manifest("m-lost", "job-1", tmp.resolve("m-lost"));
		record.setTotalResources(42);
		record.setTransactionTime(Instant.parse("2026-07-01T00:00:00Z"));
		List<ManifestRecord> store = new ArrayList<>(List.of(record));
		Instant lastCrawlStart = Instant.parse("2026-07-10T08:00:00Z");
		NdjsonExportService fakeExport = new NdjsonExportService(null, null, props) {
			@Override
			public SnapshotResult writeSnapshot(String manifestId, List<String> serverKeys) {
				Path dir = tmp.resolve(manifestId);
				try {
					Files.createDirectories(dir);
					Files.writeString(dir.resolve("Organization.ndjson"), "{}\n{}\n");
					Files.writeString(dir.resolve("counts.json"), "{\"Organization\":2}");
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				return new SnapshotResult(dir.toString(), 2);
			}
		};
		ManifestService service =
				new ManifestService(fakeExport, fakeRepo(store), fakeRunRepo(completedRun(lastCrawlStart)), props);

		ManifestRecord saved = service.regenerate(record, List.of("http://a.example/fhir"));

		Path finalDir = tmp.resolve("m-lost");
		assertTrue(Files.exists(finalDir.resolve("Organization.ndjson")), "files land in the manifest's directory");
		assertFalse(Files.exists(tmp.resolve("m-lost.regen")), "the temp build directory is moved away");
		assertEquals(finalDir.toString(), saved.getStorageDir());
		assertEquals(2, saved.getTotalResources(), "the count reflects the re-exported aggregate");
		assertEquals(
				lastCrawlStart,
				saved.getTransactionTime(),
				"transactionTime reflects when the data was last acquired, not the regeneration");
		assertEquals(2, service.render(saved, "http://x").output().get(0).count());
	}

	@Test
	void regenerateKeepsTheOriginalTransactionTimeWhenNoCompletedRunSurvives(@TempDir Path tmp) throws Exception {
		ApiProperties props = new ApiProperties();
		props.setStoragePath(tmp.toString());
		ManifestRecord record = manifest("m-lost", "job-1", tmp.resolve("m-lost"));
		Instant originalTime = Instant.parse("2026-07-01T00:00:00Z");
		record.setTransactionTime(originalTime);
		NdjsonExportService fakeExport = new NdjsonExportService(null, null, props) {
			@Override
			public SnapshotResult writeSnapshot(String manifestId, List<String> serverKeys) {
				Path dir = tmp.resolve(manifestId);
				try {
					Files.createDirectories(dir);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				return new SnapshotResult(dir.toString(), 0);
			}
		};
		ManifestService service =
				new ManifestService(fakeExport, fakeRepo(new ArrayList<>(List.of(record))), fakeRunRepo(null), props);

		ManifestRecord saved = service.regenerate(record, List.of("http://a.example/fhir"));

		assertEquals(originalTime, saved.getTransactionTime(), "no completed run to anchor to keeps the old time");
	}

	private static Path snapshotDir(Path base, String name) throws Exception {
		Path dir = Files.createDirectories(base.resolve(name));
		Files.writeString(dir.resolve("Organization.ndjson"), "{}\n");
		return dir;
	}

	private static ManifestRecord manifest(String id, String jobId, Path dir) {
		ManifestRecord record = new ManifestRecord();
		record.setId(id);
		record.setJobId(jobId);
		record.setStorageDir(dir.toString());
		return record;
	}

	private static CrawlRun completedRun(Instant startedAt) {
		CrawlRun run = new CrawlRun();
		run.setStartedAt(startedAt);
		return run;
	}

	private static CrawlRunRepository fakeRunRepo(CrawlRun latestCompleted) {
		return (CrawlRunRepository) Proxy.newProxyInstance(
				CrawlRunRepository.class.getClassLoader(),
				new Class<?>[] {CrawlRunRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findTop1ByServerKeyAndStatusOrderByStartedAtDesc" -> Optional.ofNullable(latestCompleted);
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static ManifestRepository fakeRepo(List<ManifestRecord> store) {
		return (ManifestRepository) Proxy.newProxyInstance(
				ManifestRepository.class.getClassLoader(),
				new Class<?>[] {ManifestRepository.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "findByJobIdOrderByGeneratedAtDescIdDesc" -> store.stream()
							.filter(m -> m.getJobId().equals(args[0]))
							.toList();
					case "delete" -> {
						store.remove((ManifestRecord) args[0]);
						yield null;
					}
					case "save" -> args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
