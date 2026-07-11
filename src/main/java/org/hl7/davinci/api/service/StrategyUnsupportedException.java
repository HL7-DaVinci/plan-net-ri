package org.hl7.davinci.api.service;

/** Thrown when a server rejects a crawl strategy's entry operation as unsupported. */
public class StrategyUnsupportedException extends RuntimeException {

	public StrategyUnsupportedException(String message) {
		super(message);
	}
}
