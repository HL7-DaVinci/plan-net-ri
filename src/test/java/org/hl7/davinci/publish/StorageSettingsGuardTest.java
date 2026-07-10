package org.hl7.davinci.publish;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.StorageSettings;
import java.util.Optional;
import org.hl7.davinci.publish.feed.StorageSettingsGuard;
import org.junit.jupiter.api.Test;

class StorageSettingsGuardTest {

	private static JpaStorageSettings safeJpaStorageSettings() {
		JpaStorageSettings settings = new JpaStorageSettings();
		settings.setResourceDbHistoryEnabled(true);
		settings.setExpungeEnabled(false);
		settings.setDeleteExpungeEnabled(false);
		settings.setUpdateWithHistoryRewriteEnabled(false);
		settings.setTagStorageMode(StorageSettings.TagStorageModeEnum.VERSIONED);
		return settings;
	}

	private static PartitionSettings safePartitionSettings() {
		PartitionSettings settings = new PartitionSettings();
		settings.setPartitioningEnabled(false);
		return settings;
	}

	@Test
	void passesOnSafeSettings() {
		StorageSettingsGuard guard = new StorageSettingsGuard(safeJpaStorageSettings(), safePartitionSettings());

		assertTrue(guard.firstViolation().isEmpty());
	}

	@Test
	void flagsResourceDbHistoryDisabled() {
		JpaStorageSettings settings = safeJpaStorageSettings();
		settings.setResourceDbHistoryEnabled(false);
		StorageSettingsGuard guard = new StorageSettingsGuard(settings, safePartitionSettings());

		Optional<String> violation = guard.firstViolation();

		assertTrue(violation.isPresent());
		assertTrue(violation.get().contains("resource_dbhistory_enabled"));
	}

	@Test
	void flagsExpungeEnabled() {
		JpaStorageSettings settings = safeJpaStorageSettings();
		settings.setExpungeEnabled(true);
		StorageSettingsGuard guard = new StorageSettingsGuard(settings, safePartitionSettings());

		Optional<String> violation = guard.firstViolation();

		assertTrue(violation.isPresent());
		assertTrue(violation.get().contains("expunge_enabled"));
	}

	@Test
	void flagsDeleteExpungeEnabled() {
		JpaStorageSettings settings = safeJpaStorageSettings();
		settings.setDeleteExpungeEnabled(true);
		StorageSettingsGuard guard = new StorageSettingsGuard(settings, safePartitionSettings());

		Optional<String> violation = guard.firstViolation();

		assertTrue(violation.isPresent());
		assertTrue(violation.get().contains("delete_expunge_enabled"));
	}

	@Test
	void flagsUpdateWithHistoryRewriteEnabled() {
		JpaStorageSettings settings = safeJpaStorageSettings();
		settings.setUpdateWithHistoryRewriteEnabled(true);
		StorageSettingsGuard guard = new StorageSettingsGuard(settings, safePartitionSettings());

		Optional<String> violation = guard.firstViolation();

		assertTrue(violation.isPresent());
		assertTrue(violation.get().contains("update_with_history_rewrite_enabled"));
	}

	@Test
	void flagsNonVersionedTagStorageMode() {
		JpaStorageSettings settings = safeJpaStorageSettings();
		settings.setTagStorageMode(StorageSettings.TagStorageModeEnum.NON_VERSIONED);
		StorageSettingsGuard guard = new StorageSettingsGuard(settings, safePartitionSettings());

		Optional<String> violation = guard.firstViolation();

		assertTrue(violation.isPresent());
		assertTrue(violation.get().contains("tag_storage_mode"));
	}

	@Test
	void flagsPartitioningEnabled() {
		PartitionSettings partitionSettings = safePartitionSettings();
		partitionSettings.setPartitioningEnabled(true);
		StorageSettingsGuard guard = new StorageSettingsGuard(safeJpaStorageSettings(), partitionSettings);

		Optional<String> violation = guard.firstViolation();

		assertTrue(violation.isPresent());
		assertTrue(violation.get().contains("partitioning_enabled"));
	}
}
