package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.api.service.ManifestService;
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

		ManifestService service = new ManifestService(null, fakeRepo(store), null);

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
		ManifestService service = new ManifestService(null, fakeRepo(new ArrayList<>()), null);
		assertEquals(0, service.deleteManifestsForJob("job-1"));
	}

	@Test
	void withinATransactionTheRowGoesButTheDirectorySurvivesUntilCommit(@TempDir Path tmp) throws Exception {
		List<ManifestRecord> store = new ArrayList<>();
		Path dir = snapshotDir(tmp, "a");
		store.add(manifest("m-a", "job-1", dir));
		ManifestService service = new ManifestService(null, fakeRepo(store), null);

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
		ManifestService service = new ManifestService(null, null, null);

		var output = service.render(manifest("m", "job", dir), "http://x").output();

		assertEquals(1, output.size());
		assertEquals(3, output.get(0).count(), "render counts the resource lines per type");
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
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
