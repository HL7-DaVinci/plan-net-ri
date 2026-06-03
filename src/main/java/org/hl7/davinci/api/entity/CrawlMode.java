package org.hl7.davinci.api.entity;

/** Whether a crawl run rebuilt a full snapshot or applied an incremental delta. */
public enum CrawlMode {
	FULL,
	INCREMENTAL
}
