package org.hl7.davinci.api.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One target server in a crawl job's scope. Matches the frontend {@code ScopeServer}
 * shape persisted in {@code crawl_job.servers} as JSON. {@code serverKey} is advisory;
 * the crawler always derives the canonical key from {@code url}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerScope(String serverKey, String serverLabel, String url) {}
