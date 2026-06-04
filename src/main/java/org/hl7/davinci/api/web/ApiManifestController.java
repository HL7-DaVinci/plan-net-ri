package org.hl7.davinci.api.web;

import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.entity.ManifestRecord;
import org.hl7.davinci.api.model.ManifestJson;
import org.hl7.davinci.api.model.ManifestSummary;
import org.hl7.davinci.api.repository.ManifestRepository;
import org.hl7.davinci.api.service.ManifestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Lists snapshots and serves each manifest and its NDJSON files. */
@RestController
@RequestMapping("/api")
public class ApiManifestController {

	private static final MediaType NDJSON = MediaType.parseMediaType("application/fhir+ndjson");
	private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9]+\\.ndjson");

	private final ManifestService manifestService;
	private final ManifestRepository manifestRepo;
	private final ApiProperties props;

	public ApiManifestController(
			ManifestService manifestService, ManifestRepository manifestRepo, ApiProperties props) {
		this.manifestService = manifestService;
		this.manifestRepo = manifestRepo;
		this.props = props;
	}

	@GetMapping("/manifests")
	public List<ManifestSummary> list() {
		return manifestService.listManifests();
	}

	@GetMapping("/manifests/{id}/manifest.json")
	public ManifestJson manifest(@PathVariable("id") String id) {
		return manifestService.render(requireManifest(id), baseUrl());
	}

	@DeleteMapping("/manifests/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable("id") String id) {
		if (!manifestService.deleteManifest(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Manifest not found");
		}
	}

	@GetMapping("/manifests/{id}/files/{fileName}")
	public ResponseEntity<Resource> file(@PathVariable("id") String id, @PathVariable("fileName") String fileName) {
		if (!SAFE_FILE.matcher(fileName).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name");
		}
		ManifestRecord manifest = requireManifest(id);
		Path file = Path.of(manifest.getStorageDir(), fileName);
		if (!Files.exists(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
		}
		// Serve inline with the real type name so the browser does not save it as "f.txt".
		ContentDisposition disposition = ContentDisposition.inline().filename(fileName).build();
		return ResponseEntity.ok()
				.contentType(NDJSON)
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.body(new FileSystemResource(file));
	}

	private ManifestRecord requireManifest(String id) {
		return manifestRepo
				.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Manifest not found"));
	}

	private String baseUrl() {
		String configured = props.getPublicBaseUrl();
		if (configured != null && !configured.isBlank()) {
			return configured.replaceAll("/+$", "");
		}
		return ServletUriComponentsBuilder.fromCurrentContextPath().toUriString().replaceAll("/+$", "");
	}
}
