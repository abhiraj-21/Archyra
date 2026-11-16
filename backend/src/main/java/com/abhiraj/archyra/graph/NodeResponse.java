package com.abhiraj.archyra.graph;

import java.util.Map;

import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body of {@code GET /api/v1/analyses/{analysisId}/nodes/{nodeId}}. Fields that do not apply to a
 * node are left out of the JSON.
 *
 * @param qualifiedName fully qualified name; for a file its path
 * @param file          declaring file; absent for repository and module nodes
 * @param line          1-based declaration line, when known
 * @param kind          finer declaration kind of a type ({@code CLASS}, {@code ENUM}, {@code RECORD}, ...)
 * @param language      language of a file node (display name, e.g. {@code Java}); absent for other nodes
 * @param parentId      the node that contains this one (file for a class, class for a method); absent for
 *                      the repository
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeResponse(
		String id,
		NodeType type,
		String name,
		String qualifiedName,
		String file,
		Integer line,
		String kind,
		String language,
		String parentId,
		Relationships relationships) {

	/**
	 * How many relationships of each type leave and enter the node, counting only nodes of the repository
	 * (external ones are not part of the graph the API serves). Types with no relationship are absent.
	 */
	public record Relationships(Map<RelationshipType, Long> outgoing, Map<RelationshipType, Long> incoming) {
	}
}
