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
	FAILED,

	/**
	 * A repository whose credit was spent by an account that was deleted, restored when the person came back: the link
	 * only. No graph, metadata, index or conversation exists, nothing runs, and the credit is already charged
	 * ({@code quotaCharged}). It is neither running nor finished: the owner can analyse it (moves this row to
	 * {@link #QUEUED} without charging again) or delete it (a free user gets the credit back).
	 */
	NOT_ANALYZED
}
