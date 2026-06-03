package org.hl7.davinci.api.model;

import java.util.List;

/** A page of crawl runs for a job, with the totals needed to render pagination. */
public record RunPage(List<RunResponse> runs, int page, int size, long totalElements, int totalPages) {}
