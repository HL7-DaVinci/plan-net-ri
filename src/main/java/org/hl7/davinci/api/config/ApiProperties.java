package org.hl7.davinci.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bound from the {@code api.*} section of application.yaml. */
@Component
@ConfigurationProperties(prefix = "api")
public class ApiProperties {

	/** Master switch for the crawler scheduler; REST endpoints stay available. */
	private boolean enabled = true;

	/** Directory under which per-manifest NDJSON bundles are written. */
	private String storagePath = "./target/crawler-data";

	private long pollerIntervalMs = 30_000;

	private int pageSize = 200;

	private int requestTimeoutMs = 180_000;

	/** Politeness pause between page fetches against a crawled server; 0 = none. */
	private long pageDelayMs = 0;

	/** Base URL for manifest output[].url; inbound request URL is used when null. */
	private String publicBaseUrl;

	/** JSON array of {name, url} FHIR servers exposed to the UI via /crawler/config.js. */
	private String fhirServers;

	/** Manifests retained per job; 0 = unlimited. */
	private int retentionPerJob = 5;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public void setStoragePath(String storagePath) {
		this.storagePath = storagePath;
	}

	public long getPollerIntervalMs() {
		return pollerIntervalMs;
	}

	public void setPollerIntervalMs(long pollerIntervalMs) {
		this.pollerIntervalMs = pollerIntervalMs;
	}

	public int getPageSize() {
		return pageSize;
	}

	public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
	}

	public int getRequestTimeoutMs() {
		return requestTimeoutMs;
	}

	public void setRequestTimeoutMs(int requestTimeoutMs) {
		this.requestTimeoutMs = requestTimeoutMs;
	}

	public long getPageDelayMs() {
		return pageDelayMs;
	}

	public void setPageDelayMs(long pageDelayMs) {
		this.pageDelayMs = pageDelayMs;
	}

	public String getPublicBaseUrl() {
		return publicBaseUrl;
	}

	public void setPublicBaseUrl(String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}

	public String getFhirServers() {
		return fhirServers;
	}

	public void setFhirServers(String fhirServers) {
		this.fhirServers = fhirServers;
	}

	public int getRetentionPerJob() {
		return retentionPerJob;
	}

	public void setRetentionPerJob(int retentionPerJob) {
		this.retentionPerJob = retentionPerJob;
	}
}
