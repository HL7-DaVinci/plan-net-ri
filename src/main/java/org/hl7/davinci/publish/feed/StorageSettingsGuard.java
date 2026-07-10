package org.hl7.davinci.publish.feed;

import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.StorageSettings;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Verifies HFJ_RES_VER is append-only, untouched by expunge, in-place history rewrite, or
 * partitioning, which the publish feed's change detection assumes.
 */
@Component
public class StorageSettingsGuard {

	private final JpaStorageSettings jpaStorageSettings;
	private final PartitionSettings partitionSettings;

	public StorageSettingsGuard(JpaStorageSettings jpaStorageSettings, PartitionSettings partitionSettings) {
		this.jpaStorageSettings = jpaStorageSettings;
		this.partitionSettings = partitionSettings;
	}

	/** Returns a description of the first violated assertion, or empty if all pass. */
	public Optional<String> firstViolation() {
		if (!jpaStorageSettings.isResourceDbHistoryEnabled()) {
			return Optional.of("resource_dbhistory_enabled must be true: "
					+ "the publish feed reads resource versions from the history table");
		}
		if (jpaStorageSettings.isExpungeEnabled()) {
			return Optional.of("expunge_enabled must be false: the publish feed requires an append-only version log");
		}
		if (jpaStorageSettings.isDeleteExpungeEnabled()) {
			return Optional.of(
					"delete_expunge_enabled must be false: the publish feed requires an append-only version log");
		}
		if (jpaStorageSettings.isUpdateWithHistoryRewriteEnabled()) {
			return Optional.of("update_with_history_rewrite_enabled must be false: "
					+ "rewriting history in place breaks the version-based change feed");
		}
		if (jpaStorageSettings.getTagStorageMode() != StorageSettings.TagStorageModeEnum.VERSIONED) {
			return Optional.of("tag_storage_mode must be VERSIONED: "
					+ "non-versioned tag storage decouples tags from the resource version history");
		}
		if (partitionSettings.isPartitioningEnabled()) {
			return Optional.of(
					"partitioning_enabled must be false: the publish feed does not scope reads by partition");
		}
		return Optional.empty();
	}
}
