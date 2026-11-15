package com.abhiraj.archyra.parsing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.abhiraj.archyra.ir.FileIR;

/** Read-only view of the modules and files of the repository, for {@link ModuleResolver}s. */
public interface ModuleIndex {

	boolean containsModule(String moduleName);

	Collection<String> moduleNames();

	/** The files that declare symbols in {@code moduleName}. */
	List<FileIR> files(String moduleName);

	Optional<FileIR> fileAt(String path);

	Collection<String> filePaths();
}
