package com.abhiraj.archyra.common;

/** No analysis exists for the requested id (including ids that are not well formed); mapped to HTTP 404. */
public class AnalysisNotFoundException extends RuntimeException {

	public AnalysisNotFoundException(String message) {
		super(message);
	}
}
