package com.abhiraj.archyra.common;

/** An analysis that is still queued or running cannot be deleted. Mapped to {@code 409 ANALYSIS_IN_PROGRESS}. */
public class AnalysisInProgressException extends RuntimeException {

	public AnalysisInProgressException(String message) {
		super(message);
	}
}
