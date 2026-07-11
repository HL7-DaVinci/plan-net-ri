package org.hl7.davinci.api.entity;

/** How a crawl job acquires resources. */
public enum CrawlStrategy {
	/** Resolve the best supported strategy each run: $export, then partitioned search, then search, then history. */
	AUTO,
	/** Async Bulk Data system $export. */
	BULK_EXPORT,
	/** System-level _history paging. */
	HISTORY,
	/** Incremental _lastUpdated search plus _history?_since deletions. */
	SEARCH,
	/** Search paging by an advancing _lastUpdated watermark instead of page links. */
	SEARCH_LAST_UPDATED,
	/** Search paging by _lastUpdated watermarks within census-bounded time windows, crawled in parallel. */
	SEARCH_LAST_UPDATED_PARTITIONED
}
