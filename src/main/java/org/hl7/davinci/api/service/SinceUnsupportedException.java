package org.hl7.davinci.api.service;

/** Thrown when a $export kick-off carrying _since is rejected; the server may still support a bare export. */
public class SinceUnsupportedException extends RuntimeException {

	public SinceUnsupportedException(String message) {
		super(message);
	}
}
