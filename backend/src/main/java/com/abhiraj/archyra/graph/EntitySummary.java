package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.NodeType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A declaration of the repository, as listed in the {@code /files/{fileId}} and {@code /classes/{classId}}
 * responses.
 *
 * @param kind     finer declaration kind of a type ({@code CLASS}, {@code ENUM}, {@code RECORD}, ...)
 * @param line     1-based declaration line, when known
 * @param parentId the declaration that contains this one (a file, an enclosing class); absent when the
 *                 list already says so
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntitySummary(String id, NodeType type, String name, String kind, Integer line, String parentId) {
}
