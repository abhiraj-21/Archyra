package com.abhiraj.archyra.common;

/** The analysis has no such node (including ids that cannot belong to a node); mapped to HTTP 404. */
public class NodeNotFoundException extends RuntimeException {

	public NodeNotFoundException(String message) {
		super(message);
	}
}
