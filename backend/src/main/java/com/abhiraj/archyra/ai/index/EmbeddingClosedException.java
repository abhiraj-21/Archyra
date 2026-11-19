package com.abhiraj.archyra.ai.index;

/**
 * The embedding model was closed (the application is shutting down). Not a failure of the index: a job that meets it stops
 * and leaves the index INDEXING, so the startup step resumes it where it stopped.
 */
public class EmbeddingClosedException extends IllegalStateException {

	public EmbeddingClosedException() {
		super("The embedding model is closed: the application is shutting down");
	}
}
