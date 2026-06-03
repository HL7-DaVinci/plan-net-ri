package org.hl7.davinci.api.model;

/** Returned by POST /api/jobs/{id}/run with the batchId of the dispatched crawl. */
public record RunTriggerResponse(String batchId) {}
