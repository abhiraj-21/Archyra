package com.abhiraj.archyra.parsing;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

/** Hands the configured native library directory to {@link NativeLibraries} at startup. */
@Component
class NativeLibrariesConfig {

	NativeLibrariesConfig(ParsingProperties properties) {
		NativeLibraries.configure(Path.of(properties.nativeLibraryDir()));
	}
}
