package com.abhiraj.archyra.graph;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body of {@code GET /api/v1/analyses/{analysisId}/classes/{classId}}. Every list is present, empty
 * when there is nothing of that kind.
 *
 * @param kind         declaration kind: {@code CLASS}, {@code ENUM}, {@code RECORD}, ...
 * @param file         declaring file
 * @param parentId     the enclosing class of a nested class, otherwise the declaring file
 * @param superclasses what the class extends (several in languages with multiple inheritance); may be external
 * @param methods      methods declared directly in the class
 * @param interfaces   interfaces the class implements; may be external (e.g. {@code Serializable})
 * @param dependencies classes and interfaces of the repository this class depends on (the {@code DEPENDS_ON}
 *                     relationship that draws the architecture); never external
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassResponse(
		String id,
		String name,
		String qualifiedName,
		String kind,
		String file,
		Integer line,
		String parentId,
		List<NodeRef> superclasses,
		List<EntitySummary> methods,
		List<NodeRef> interfaces,
		List<NodeRef> dependencies) {
}
