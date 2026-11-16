package com.abhiraj.archyra.graph;

import com.abhiraj.archyra.ir.Language;

/** A repository file that is part of the graph (a {@code File} node). */
public record FileInfo(String id, String name, String path, Language language) {
}
