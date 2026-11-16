package com.abhiraj.archyra.common;

/** Results were requested for an analysis that has no results (yet, or ever); mapped to HTTP 409. */
public class AnalysisNotCompletedException extends RuntimeException {

	public AnalysisNotCompletedException(String message) {
		super(message);
	}
}
