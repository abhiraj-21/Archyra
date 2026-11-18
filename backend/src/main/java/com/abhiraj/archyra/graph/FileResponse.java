package com.abhiraj.archyra.graph;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body of {@code GET /api/v1/analyses/{analysisId}/files/{fileId}}: the file and what was extracted
 * from it. Every list is present, empty when the file has nothing of that kind.
 *
 * @param path       repository-relative path
 * @param language   language display name, e.g. {@code Java}
 * @param test       whether the file is test code (path and file-name conventions)
 * @param module     the module (Java package, Python module, ...) the file belongs to
 * @param classes    classes, enums and records declared in the file, nested ones included
 * @param interfaces interfaces and annotation types declared in the file
 * @param methods    methods of those types
 * @param functions  free functions (languages without classes, or scripts)
 * @param imports    what the file imports: other files of the repository, and external types or modules
 * @param references types and modules the file's declarations use (field types, signatures, {@code new}),
 *                   other than those declared in the file itself
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileResponse(
		String id,
		String name,
		String path,
		String language,
		boolean test,
		NodeRef module,
		List<EntitySummary> classes,
		List<EntitySummary> interfaces,
		List<EntitySummary> methods,
		List<EntitySummary> functions,
		List<NodeRef> imports,
		List<NodeRef> references) {
}
