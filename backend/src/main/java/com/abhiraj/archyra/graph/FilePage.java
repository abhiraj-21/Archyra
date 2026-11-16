package com.abhiraj.archyra.graph;

import java.util.List;

/** One page of files, sorted by path, and the number of files that match the filters in all. */
public record FilePage(List<FileInfo> files, long total) {
}
