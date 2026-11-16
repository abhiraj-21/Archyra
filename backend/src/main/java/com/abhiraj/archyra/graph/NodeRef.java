package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.NodeType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A pointer to another node, as listed in the {@code /files/{fileId}} and {@code /classes/{classId}}
 * responses. {@code id} works with {@code /nodes/{nodeId}} when the target is part of the repository.
 *
 * @param qualifiedName fully qualified name, e.g. {@code java.util.List} for an external type
 * @param file          declaring file; absent for external targets
 * @param external      true when the target is outside the repository (a library or runtime type); such a
 *                      node is listed but cannot be opened with {@code /nodes/{nodeId}}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeRef(String id, NodeType type, String name, String qualifiedName, String file, boolean external) {
}
