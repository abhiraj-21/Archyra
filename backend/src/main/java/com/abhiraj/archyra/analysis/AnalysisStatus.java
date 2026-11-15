package com.abhiraj.archyra.analysis;

/**
 * Lifecycle of an analysis. Names match the API values in Architecture.md exactly.
 */
public enum AnalysisStatus {
	QUEUED,
	CLONING,
	PARSING,
	ANALYZING,
	BUILDING_GRAPH,
	COMPLETED,
	FAILED
}
