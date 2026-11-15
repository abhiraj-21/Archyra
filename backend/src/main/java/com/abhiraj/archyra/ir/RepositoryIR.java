package com.abhiraj.archyra.ir;

import java.util.List;

/** All parsed files of one repository: the input of reference resolution. */
public record RepositoryIR(String name, String url, List<FileIR> files) {

	public RepositoryIR {
		files = List.copyOf(files);
	}
}
