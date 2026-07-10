package org.hl7.davinci.api.web;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.publish.PublishProperties;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Serves $bulk-publish output files. Content at a URL is immutable (a fresh snapshot id is used
 * for every publish), so these are cacheable forever.
 */
@RestController
@RequestMapping("/api/publish")
public class PublishFileController {

	private static final MediaType NDJSON = MediaType.parseMediaType("application/fhir+ndjson");
	private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
	private static final Pattern SNAPSHOT_ID =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private final PublishProperties publishProps;
	private final FhirContext fhirContext;

	public PublishFileController(PublishProperties publishProps, FhirContext fhirContext) {
		this.publishProps = publishProps;
		this.fhirContext = fhirContext;
	}

	@GetMapping("/{snapshotId}/{fileName}")
	public ResponseEntity<StreamingResponseBody> file(
			@PathVariable("snapshotId") String snapshotId,
			@PathVariable("fileName") String fileName,
			@RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
			@RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
		if (!SNAPSHOT_ID.matcher(snapshotId).matches()
				|| !NdjsonFiles.SAFE_FILE.matcher(fileName).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot id or file name");
		}
		if (!acceptsNdjson(accept)) {
			return notAcceptable();
		}
		Path gz = Path.of(publishProps.getStoragePath(), snapshotId, fileName + ".gz");
		if (!Files.exists(gz)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
		}

		ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
				.contentType(NDJSON)
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
				.header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);

		if (clientAcceptsGzip(acceptEncoding)) {
			builder.header(HttpHeaders.CONTENT_ENCODING, "gzip");
			StreamingResponseBody body = out -> {
				try (InputStream in = Files.newInputStream(gz)) {
					in.transferTo(out);
				}
			};
			return builder.body(body);
		}

		StreamingResponseBody body = out -> {
			try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
				in.transferTo(out);
			}
		};
		return builder.body(body);
	}

	private static boolean clientAcceptsGzip(String acceptEncoding) {
		return acceptEncoding != null && acceptEncoding.toLowerCase(Locale.ROOT).contains("gzip");
	}

	private static boolean acceptsNdjson(String accept) {
		if (accept == null || accept.isBlank()) {
			return true;
		}
		String lower = accept.toLowerCase(Locale.ROOT);
		return lower.contains("application/fhir+ndjson") || lower.contains("*/*") || lower.contains("application/*");
	}

	private ResponseEntity<StreamingResponseBody> notAcceptable() {
		OperationOutcome outcome = new OperationOutcome();
		OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
		issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
		issue.setCode(OperationOutcome.IssueType.NOTSUPPORTED);
		issue.setDiagnostics("Only application/fhir+ndjson is supported.");
		byte[] body = fhirContext
				.newJsonParser()
				.setPrettyPrint(false)
				.encodeResourceToString(outcome)
				.getBytes(StandardCharsets.UTF_8);
		return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
				.contentType(FHIR_JSON)
				.header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
				.body(out -> out.write(body));
	}
}
