package com.abhiraj.archyra.codebase;

/**
 * A framework, library or tool the repository uses, as shown in {@link RepositoryResponse#technologies()}.
 *
 * @param name     what it is called, for example {@code Spring Boot} or {@code Maven}
 * @param category the {@code TechnologyCategory} name ({@code BACKEND_FRAMEWORK}, {@code BUILD_TOOL}, {@code LIBRARY}, ...)
 */
public record TechnologyResponse(String name, String category) {
}
