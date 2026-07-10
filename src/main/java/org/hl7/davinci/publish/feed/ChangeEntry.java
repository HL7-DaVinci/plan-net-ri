package org.hl7.davinci.publish.feed;

/** One resource's winning state within a change feed window. */
public record ChangeEntry(String type, String id, long versionId, boolean deleted, long updatedMillis) {}
