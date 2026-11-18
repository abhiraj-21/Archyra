package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.Language;

/** A repository file that is part of the graph (a {@code File} node); {@code test} says it is test code. */
public record FileInfo(String id, String name, String path, Language language, boolean test) {
}
