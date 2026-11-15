package com.abhiraj.archyra.common;

/**
 * An expected, user-explainable reason for an analysis to fail (repository too large, clone
 * failed, ...). The message is stored as the analysis failure reason and later shown by the API,
 * so it must never contain paths, stack traces or other internals.
 */
public class AnalysisFailureException extends RuntimeException {

	public AnalysisFailureException(String reason) {
		super(reason);
	}

	public AnalysisFailureException(String reason, Throwable cause) {
		super(reason, cause);
	}
}
