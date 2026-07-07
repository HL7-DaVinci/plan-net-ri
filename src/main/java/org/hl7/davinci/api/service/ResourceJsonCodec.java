package org.hl7.davinci.api.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Compresses stored resource bodies to gzip bytes for the binary {@code resource_json} column. */
public final class ResourceJsonCodec {

	private ResourceJsonCodec() {}

	public static byte[] encode(String json) {
		if (json == null) {
			return null;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes) {
			{
				def.setLevel(Deflater.BEST_COMPRESSION);
			}
		}) {
			gzip.write(json.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to compress resource body", e);
		}
		return bytes.toByteArray();
	}

	public static String decode(byte[] stored) {
		if (stored == null) {
			return null;
		}
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(stored))) {
			return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to decompress resource body", e);
		}
	}
}
