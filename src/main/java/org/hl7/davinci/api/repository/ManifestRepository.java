package org.hl7.davinci.api.repository;

import org.hl7.davinci.api.entity.ManifestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManifestRepository extends JpaRepository<ManifestRecord, String> {

	List<ManifestRecord> findAllByOrderByGeneratedAtDescIdDesc();

	List<ManifestRecord> findByJobIdOrderByGeneratedAtDescIdDesc(String jobId);
}
