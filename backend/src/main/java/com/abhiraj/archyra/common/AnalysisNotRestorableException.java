package com.abhiraj.archyra.common;

/**
 * Analysing a restored entry was asked for an analysis that is not a {@code NOT_ANALYZED} entry (it is running, completed
 * or failed). Mapped to {@code 409 ANALYSIS_NOT_RESTORABLE}.
 */
public class AnalysisNotRestorableException extends RuntimeException {

	public AnalysisNotRestorableException(String message) {
		super(message);
	}
}
