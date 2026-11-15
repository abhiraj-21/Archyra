package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.Declaration;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class RustExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("rust");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void derivesModulePathsFromFileLayout() {
		assertThat(file("src/main.rs").moduleName()).isEqualTo("src");
		assertThat(file("src/model/mod.rs").moduleName()).isEqualTo("src.model");
		assertThat(file("src/model/user.rs").moduleName()).isEqualTo("src.model.user");
		assertThat(file("src/service/server.rs").moduleName()).isEqualTo("src.service.server");
	}

	@Test
	void normalisesUsePathsIncludingListsAndSuper() {
		assertThat(file("src/service/server.rs").imports()).extracting("module", "name", "relativeLevel")
				.contains(tuple("std.collections", "HashMap", 0), tuple("repo", "MemoryRepo", 1),
						tuple("repo", "Repo", 1), tuple("crate.model.user", "User", 0));
		assertThat(file("src/main.rs").imports()).extracting("module", "name")
				.contains(tuple("self.model", null), tuple("self.service", null), tuple("crate.service.server", "Server"));
	}

	@Test
	void extractsStructsTraitsAndImplBlocksAsExtensions() {
		FileIR repo = file("src/service/repo.rs");
		assertThat(repo.types()).extracting(TypeIR::kind).contains(TypeKind.TRAIT, TypeKind.STRUCT);
		TypeIR trait = repo.types().get(1);
		assertThat(trait.name()).isEqualTo("Repo");
		assertThat(trait.extendsTypes()).extracting("name").containsExactly("Base");
		assertThat(trait.methods()).extracting(MethodIR::name).containsExactly("save");

		TypeIR impl = repo.types().get(3);
		assertThat(impl.declaration()).isEqualTo(Declaration.EXTENSION);
		assertThat(impl.name()).isEqualTo("MemoryRepo");
		assertThat(impl.implementsTypes()).extracting("name").containsExactly("Repo");
	}

	@Test
	void looksThroughSmartPointersWhenTypingFields() {
		TypeIR server = file("src/service/server.rs").types().getFirst();

		assertThat(server.fields()).extracting("name").containsExactly("repo", "cache");
		assertThat(server.fields().getFirst().type().name()).isEqualTo("Repo");
		assertThat(server.fields().get(1).type().name()).isEqualTo("HashMap");
	}

	// ---- resolved graph -----------------------------------------------------------------------

	@Test
	void mergesImplBlocksAndTraitImplementations() {
		assertThat(ANALYSIS.edge("class:src.model.user.User", RelationshipType.CONTAINS,
				"method:src.model.user.User#new(&str)")).isFalse(); // the parameter type is str, a reference
		assertThat(ANALYSIS.graph.edges()).anyMatch(e -> e.source().equals("class:src.model.user.User")
				&& e.type() == RelationshipType.CONTAINS && e.target().startsWith("method:src.model.user.User#new("));
		assertThat(ANALYSIS.edge("class:src.service.repo.MemoryRepo", RelationshipType.IMPLEMENTS,
				"interface:src.service.repo.Repo")).isTrue();
		assertThat(ANALYSIS.edge("interface:src.service.repo.Repo", RelationshipType.EXTENDS,
				"interface:src.service.repo.Base")).isTrue();
		assertThat(ANALYSIS.node("interface:src.service.repo.Repo").kind()).isEqualTo("TRAIT");
	}

	@Test
	void resolvesUseDeclarationsToFiles() {
		String server = "file:src/service/server.rs";
		assertThat(ANALYSIS.edge(server, RelationshipType.IMPORTS, "file:src/service/repo.rs")).isTrue();
		assertThat(ANALYSIS.edge(server, RelationshipType.IMPORTS, "file:src/model/user.rs")).isTrue();
		assertThat(ANALYSIS.edge(server, RelationshipType.IMPORTS, "external:module:std.collections")).isTrue();
		assertThat(ANALYSIS.edge("file:src/model/user.rs", RelationshipType.IMPORTS, "external:module:serde")).isTrue();
		assertThat(ANALYSIS.edge("file:src/main.rs", RelationshipType.IMPORTS, "file:src/service/server.rs")).isTrue();
		// mod declarations link the child module files
		assertThat(ANALYSIS.edge("file:src/model/mod.rs", RelationshipType.IMPORTS, "file:src/model/user.rs")).isTrue();
	}

	@Test
	void resolvesCallsThroughSelfFieldsStaticPathsAndReturnSelf() {
		String start = "method:src.service.server.Server#start(User)";
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "method:src.service.repo.Repo#save(User)")).isTrue();
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "method:src.model.user.User#display()")).isTrue();
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "method:src.service.server.Server#log()")).isTrue();
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "function:src/service/server.rs#helper(i32)")).isTrue();
	}

	@Test
	void typesLocalsThroughConstructorReturnTypes() {
		String main = "function:src/main.rs#main()";
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:src.service.server.Server#new()")).isTrue();
		// server is typed by Server::new -> Self, so server.start resolves
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:src.service.server.Server#start(User)")).isTrue();
	}

	@Test
	void derivesDependenciesBetweenTypesAndModules() {
		assertThat(ANALYSIS.edge("class:src.service.server.Server", RelationshipType.DEPENDS_ON,
				"interface:src.service.repo.Repo")).isTrue();
		assertThat(ANALYSIS.edge("class:src.service.server.Server", RelationshipType.DEPENDS_ON,
				"class:src.model.user.User")).isTrue();
		assertThat(ANALYSIS.edge("module:src/service", RelationshipType.DEPENDS_ON, "module:src/model")).isTrue();
	}
}
