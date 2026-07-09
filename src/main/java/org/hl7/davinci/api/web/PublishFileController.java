package org.hl7.davinci.api.web;

import org.hl7.davinci.common.NdjsonFiles;
import org.hl7.davinci.publish.PublishProperties;
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
	private static final Pattern SNAPSHOT_ID =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private final PublishProperties publishProps;

	public PublishFileController(PublishProperties publishProps) {
		this.publishProps = publishProps;
	}

	@GetMapping("/{snapshotId}/{fileName}")
	public ResponseEntity<StreamingResponseBody> file(
			@PathVariable("snapshotId") String snapshotId,
			@PathVariable("fileName") String fileName,
			@RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {
		if (!SNAPSHOT_ID.matcher(snapshotId).matches()
				|| !NdjsonFiles.SAFE_FILE.matcher(fileName).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot id or file name");
		}
		Path gz = Path.of(publishProps.getStoragePath(), snapshotId, fileName + ".gz");
		if (!Files.exists(gz)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
		}

		ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
				.contentType(NDJSON)
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");

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
}
