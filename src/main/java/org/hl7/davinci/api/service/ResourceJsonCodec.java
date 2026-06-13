package org.hl7.davinci.api.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses stored resource bodies. Encoded values carry the {@code gz:} prefix; anything else
 * is treated as legacy plaintext, so rows written before compression remain readable in place.
 */
public final class ResourceJsonCodec {

	private static final String PREFIX = "gz:";

	private ResourceJsonCodec() {}

	public static String encode(String json) {
		if (json == null) {
			return null;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
			gzip.write(json.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to compress resource body", e);
		}
		return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	public static String decode(String stored) {
		if (stored == null || !stored.startsWith(PREFIX)) {
			return stored;
		}
		byte[] compressed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to decompress resource body", e);
		}
	}
}
