package com.abhiraj.archyra.ai.retrieval;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * What retrieval needs to know about a question.
 *
 * @param analysisId       the analysis, already checked for ownership by the caller
 * @param message          the question
 * @param previousQuestion the user's previous question in the conversation, or null; used when the question is too short
 *                         to stand alone ("explain that class")
 * @param stickyNodeIds    nodes the previous answer cited
 */
public record RetrievalRequest(UUID analysisId, String message, String previousQuestion, Collection<String> stickyNodeIds) {

	public RetrievalRequest {
		stickyNodeIds = stickyNodeIds == null ? List.of() : List.copyOf(stickyNodeIds);
	}

	public static RetrievalRequest of(UUID analysisId, String message) {
		return new RetrievalRequest(analysisId, message, null, List.of());
	}
}
