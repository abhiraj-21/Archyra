package com.abhiraj.archyra.ir;

/**
 * A framework, library, tool or platform found in the repository.
 *
 * @param name      display name, e.g. {@code Spring Boot}
 * @param ecosystem package ecosystem or tool family it was found through, e.g. {@code npm}, {@code maven}
 * @param evidence  repository-relative file that revealed it, e.g. {@code package.json}
 */
public record TechnologyIR(String name, TechnologyCategory category, String ecosystem, String evidence) {
}
