package com.abhiraj.archyra.codebase;

/** A file in the {@code /files} listing; {@code id} is its node id, {@code language} its display name. */
public record FileSummary(String id, String name, String path, String language) {
}
