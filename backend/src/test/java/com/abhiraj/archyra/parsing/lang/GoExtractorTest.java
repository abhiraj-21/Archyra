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

class GoExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("go");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void aPackageIsItsDirectory() {
		assertThat(file("internal/store/store.go").moduleName()).isEqualTo("internal.store");
		assertThat(file("internal/store/server.go").moduleName()).isEqualTo("internal.store");
		assertThat(file("internal/store/store.go").packageName()).isEqualTo("internal/store");
	}

	@Test
	void importsBindThePackageNameAsAModuleAlias() {
		assertThat(file("internal/store/store.go").imports()).extracting("module", "name", "alias")
				.containsExactly(tuple("fmt", null, "fmt"), tuple("github.com/acme/app/internal/model", null, "model"));
	}

	@Test
	void extractsStructsWithEmbeddingAndInterfaces() {
		TypeIR repo = file("internal/store/store.go").types().get(0);
		assertThat(repo.kind()).isEqualTo(TypeKind.INTERFACE);
		assertThat(repo.methods()).extracting(MethodIR::name).containsExactly("Save", "Find");

		TypeIR server = file("internal/store/store.go").types().get(2);
		assertThat(server.kind()).isEqualTo(TypeKind.STRUCT);
		assertThat(server.extendsTypes()).extracting("name").containsExactly("Base");
		assertThat(server.fields()).extracting("name").containsExactly("Base", "repo", "log");
		assertThat(server.fields().get(2).type().name()).isEqualTo("fmt.Stringer");
	}

	@Test
	void methodsWithAReceiverAreExtensionsOfTheirType() {
		TypeIR extension = file("internal/store/server.go").types().getFirst();

		assertThat(extension.declaration()).isEqualTo(Declaration.EXTENSION);
		assertThat(extension.qualifiedName()).isEqualTo("internal.store.Server");
		assertThat(extension.methods()).extracting(MethodIR::name).containsExactly("Start", "Stop");
		assertThat(file("internal/store/server.go").functions()).extracting(MethodIR::name).containsExactly("helper");
	}

	// ---- resolved graph -----------------------------------------------------------------------

	@Test
	void mergesReceiverMethodsFromAnotherFileIntoTheStruct() {
		assertThat(ANALYSIS.edge("class:internal.store.Server", RelationshipType.CONTAINS,
				"method:internal.store.Server#Start()")).isTrue();
		assertThat(ANALYSIS.edge("class:internal.store.Server", RelationshipType.CONTAINS,
				"method:internal.store.Server#Stop()")).isTrue();
		assertThat(ANALYSIS.node("class:internal.store.Server").kind()).isEqualTo("STRUCT");
		assertThat(ANALYSIS.node("class:internal.store.Server").file()).isEqualTo("internal/store/store.go");
	}

	@Test
	void resolvesEmbeddingAndImports() {
		assertThat(ANALYSIS.edge("class:internal.store.Server", RelationshipType.EXTENDS, "class:internal.store.Base")).isTrue();
		assertThat(ANALYSIS.edge("file:internal/store/store.go", RelationshipType.IMPORTS, "file:internal/model/user.go")).isTrue();
		assertThat(ANALYSIS.edge("file:internal/store/store.go", RelationshipType.IMPORTS, "external:module:fmt")).isTrue();
		assertThat(ANALYSIS.edge("file:cmd/app/main.go", RelationshipType.IMPORTS, "file:internal/store/store.go")).isTrue();
	}

	@Test
	void resolvesCallsThroughReceiversFieldsPackagesAndReturnTypes() {
		String start = "method:internal.store.Server#Start()";
		// s.repo.Save(u): receiver variable s -> field repo -> interface method
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "method:internal.store.Repo#Save(model.User)")).isTrue();
		// model.NewUser(...) is a function of another package
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "function:internal/model/user.go#NewUser(string)")).isTrue();
		// u := model.NewUser(...) is typed by NewUser's result, so u.Display() resolves
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "method:internal.model.User#Display()")).isTrue();
		// helper(1, 2) is a package-level function in another file of the package
		assertThat(ANALYSIS.edge(start, RelationshipType.CALLS, "function:internal/store/server.go#helper(int,int)")).isTrue();
	}

	@Test
	void resolvesCrossPackageFunctionAndMethodCallsInMain() {
		String main = "function:cmd/app/main.go#main()";
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "function:internal/store/store.go#NewServer(Repo)")).isTrue();
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:internal.store.Server#Start()")).isTrue();
	}

	@Test
	void callsIntoExternalPackagesAreExternalFunctions() {
		assertThat(ANALYSIS.edge("function:internal/store/store.go#NewServer(Repo)", RelationshipType.CALLS,
				"external:function:fmt#Println")).isTrue();
	}

	@Test
	void derivesDependenciesBetweenPackages() {
		assertThat(ANALYSIS.edge("module:internal/store", RelationshipType.DEPENDS_ON, "module:internal/model")).isTrue();
		assertThat(ANALYSIS.edge("module:cmd/app", RelationshipType.DEPENDS_ON, "module:internal/store")).isTrue();
		assertThat(ANALYSIS.edge("class:internal.store.Server", RelationshipType.DEPENDS_ON, "interface:internal.store.Repo")).isTrue();
	}
}
