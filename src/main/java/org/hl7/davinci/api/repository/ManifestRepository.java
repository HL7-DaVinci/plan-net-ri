package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.ManifestRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManifestRepository extends JpaRepository<ManifestRecord, String> {

	List<ManifestRecord> findAllByOrderByGeneratedAtDescIdDesc();

	List<ManifestRecord> findByJobIdOrderByGeneratedAtDescIdDesc(String jobId);
}
