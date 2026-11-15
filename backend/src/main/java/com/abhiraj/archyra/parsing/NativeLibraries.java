package com.abhiraj.archyra.parsing;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.abhiraj.archyra.common.AnalysisFailureException;

import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.NativeLibraryLookup;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place that loads Tree-sitter native code.
 *
 * <p>jtreesitter ships no native libraries. The tree-sitter runtime ({@code libtree-sitter}) and each
 * grammar ({@code libtree-sitter-java}, {@code libtree-sitter-python}, ...) are looked up in one directory, configured with
 * {@code archyra.parsing.native-library-dir} and built by {@code scripts/build-native.sh}.
 *
 * <p>This class is also registered as jtreesitter's {@link NativeLibraryLookup} service
 * ({@code META-INF/services}), which is how the runtime library is found. It has to be a public class
 * with a public no-argument constructor for that reason.
 */
@Slf4j
public final class NativeLibraries implements NativeLibraryLookup {

	private static final String RUNTIME = "tree-sitter";
	private static final String DEFAULT_DIRECTORY = "native";

	private static volatile Path directory = Path.of(DEFAULT_DIRECTORY);
	private static final Map<String, Language> LANGUAGES = new ConcurrentHashMap<>();

	/** Required by {@link java.util.ServiceLoader}. */
	public NativeLibraries() {
	}

	/** Sets the directory that holds the native libraries. Call before the first parse. */
	public static void configure(Path nativeLibraryDirectory) {
		directory = nativeLibraryDirectory.toAbsolutePath();
		LANGUAGES.clear();
	}

	@Override
	public SymbolLookup get(Arena arena) {
		Path runtime = libraryPath(RUNTIME);
		if (!Files.isRegularFile(runtime)) {
			return name -> Optional.empty(); // let jtreesitter fall back to java.library.path
		}
		return SymbolLookup.libraryLookup(runtime, arena);
	}

	/**
	 * Returns the grammar for {@code name} (for example {@code "java"}), loading it on first use.
	 *
	 * @throws AnalysisFailureException if the runtime or grammar library is missing or cannot be loaded
	 */
	public static Language language(String name) {
		return LANGUAGES.computeIfAbsent(name, NativeLibraries::loadLanguage);
	}

	private static Language loadLanguage(String name) {
		Path runtime = libraryPath(RUNTIME);
		Path grammar = libraryPath("tree-sitter-" + name);
		for (Path library : new Path[] {runtime, grammar}) {
			if (!Files.isRegularFile(library)) {
				log.error("Tree-sitter native library {} not found. Run scripts/build-native.sh or set "
						+ "archyra.parsing.native-library-dir.", library);
				throw new AnalysisFailureException("The source code parser is not available on this server");
			}
		}
		try {
			SymbolLookup lookup = SymbolLookup.libraryLookup(grammar, Arena.global());
			return Language.load(lookup, "tree_sitter_" + name.replace('-', '_'));
		}
		catch (RuntimeException | LinkageError ex) {
			log.error("Could not load the tree-sitter grammar '{}' from {}", name, grammar, ex);
			throw new AnalysisFailureException("The source code parser is not available on this server", ex);
		}
	}

	private static Path libraryPath(String libraryName) {
		return directory.resolve(System.mapLibraryName(libraryName));
	}
}
