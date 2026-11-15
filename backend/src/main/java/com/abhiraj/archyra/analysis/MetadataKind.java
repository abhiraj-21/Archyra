package com.abhiraj.archyra.analysis;

/** What a row of {@code analysis_metadata} describes. */
public enum MetadataKind {
	/** A language found in the repository, with its file count. */
	LANGUAGE,
	/** A framework, library, tool or platform the repository uses. */
	TECHNOLOGY
}
