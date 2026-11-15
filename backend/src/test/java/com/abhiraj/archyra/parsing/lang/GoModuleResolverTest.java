package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.parsing.ModuleIndex;

class GoModuleResolverTest {

	private final GoModuleResolver resolver = new GoModuleResolver();

	/** A repository whose Go packages live in the given directories. */
	private static ModuleIndex indexOf(String... directories) {
		Map<String, List<FileIR>> modules = new java.util.LinkedHashMap<>();
		for (String directory : directories) {
			String module = directory.replace('/', '.');
			modules.put(module, List.of(new FileIR(directory + "/a.go", Language.GO, module, directory, List.of(), List.of(), List.of())));
		}
		return new ModuleIndex() {
			@Override public boolean containsModule(String moduleName) { return modules.containsKey(moduleName); }
			@Override public Collection<String> moduleNames() { return modules.keySet(); }
			@Override public List<FileIR> files(String moduleName) { return modules.getOrDefault(moduleName, List.of()); }
			@Override public Optional<FileIR> fileAt(String path) { return Optional.empty(); }
			@Override public Collection<String> filePaths() { return List.of(); }
		};
	}

	private Optional<String> resolve(String importPath, String... directories) {
		FileIR from = new FileIR("main.go", Language.GO, "", "", List.of(), List.of(), List.of());
		return resolver.resolve(from, importPath, 0, indexOf(directories)).map(ref -> ref.module());
	}

	@Test
	void matchesAPackageByItsDirectoryAtTheRepositoryRoot() {
		assertThat(resolve("github.com/acme/app/internal/store", "internal/store", "internal/model"))
				.contains("internal.store");
		assertThat(resolve("github.com/acme/app/store", "store")).contains("store");
	}

	@Test
	void matchesWhenTheGoModuleLivesInASubdirectory() {
		assertThat(resolve("github.com/acme/app/internal/store", "services/api/internal/store", "services/api/internal/model"))
				.contains("services.api.internal.store");
	}

	@Test
	void prefersTheLongestMatch() {
		assertThat(resolve("github.com/acme/app/internal/store", "a/internal/store", "internal/store"))
				.contains("a.internal.store");
	}

	@Test
	void aSingleSegmentSuffixOfANestedDirectoryDoesNotMatchAThirdPartyPackage() {
		assertThat(resolve("github.com/other/lib/util", "tools/cli/util")).isEmpty();
		assertThat(resolve("fmt", "internal/store")).isEmpty();
	}
}
