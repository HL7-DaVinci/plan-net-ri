package org.hl7.davinci.api.entity;

/** How a crawl job acquires resources. */
public enum CrawlStrategy {
	/** Async Bulk Data system $export. */
	BULK_EXPORT,
	/** System-level _history paging. */
	HISTORY,
	/** Incremental _lastUpdated search plus _history?_since deletions. */
	SEARCH
}
