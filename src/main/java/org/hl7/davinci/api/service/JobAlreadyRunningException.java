package org.hl7.davinci.api.service;

/** Thrown when a crawl is requested for a job that is already in flight. */
public class JobAlreadyRunningException extends RuntimeException {

	public JobAlreadyRunningException(String jobId) {
		super("Crawl job already running: " + jobId);
	}
}
