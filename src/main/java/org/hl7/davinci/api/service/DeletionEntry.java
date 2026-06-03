package org.hl7.davinci.api.service;

/** A resource removed from the directory, discovered via system _history. */
public record DeletionEntry(String resourceType, String id) {}
