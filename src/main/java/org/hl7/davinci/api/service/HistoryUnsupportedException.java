package org.hl7.davinci.api.service;

/** Thrown when a server does not support system-level _history for deletion detection. */
public class HistoryUnsupportedException extends RuntimeException {

	public HistoryUnsupportedException(String serverUrl) {
		super("Server does not support system-level _history: " + serverUrl);
	}
}
