package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class KotlinExtractorTest {

	private static final String SERVICE = "src/main/kotlin/com/acme/service/Service.kt";
	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("kotlin");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void usesThePackageAsTheModuleAndExtractsImports() {
		FileIR service = file(SERVICE);

		assertThat(service.moduleName()).isEqualTo("com.acme.service");
		assertThat(service.imports()).extracting("module", "name", "wildcard").containsExactly(
				tuple("com.acme.model", "User", false), tuple("com.acme.repo", "Repo", false),
				tuple("com.acme.util", null, true), tuple("java.io", "Closeable", false));
	}

	@Test
	void separatesSuperclassFromInterfacesAndPromotesConstructorProperties() {
		TypeIR service = file(SERVICE).types().getFirst();

		assertThat(service.extendsTypes()).extracting("name").containsExactly("BaseService");
		assertThat(service.implementsTypes()).extracting("name").containsExactly("Closeable");
		assertThat(service.fields()).extracting("name").contains("repo", "cache");
		assertThat(service.fields().stream().filter(f -> f.name().equals("cache")).findFirst().orElseThrow().type().name())
				.isEqualTo("HashMap");
		assertThat(service.methods()).extracting(MethodIR::name).contains("Service", "load", "audit", "close", "create");
		assertThat(service.methods().getFirst().constructor()).isTrue();
		assertThat(file("src/main/kotlin/com/acme/repo/Repo.kt").types().getFirst().kind()).isEqualTo(TypeKind.INTERFACE);
	}

	@Test
	void topLevelFunctionsAreFunctions() {
		assertThat(file("src/main/kotlin/com/acme/util/Util.kt").functions()).extracting(MethodIR::name).containsExactly("helper");
		assertThat(file("src/main/kotlin/com/acme/Main.kt").functions()).extracting(MethodIR::name).containsExactly("main");
	}

	@Test
	void resolvesHeritageAndImports() {
		assertThat(ANALYSIS.edge("class:com.acme.service.Service", RelationshipType.EXTENDS,
				"class:com.acme.service.BaseService")).isTrue();
		assertThat(ANALYSIS.edge("class:com.acme.service.Service", RelationshipType.IMPLEMENTS,
				"external:interface:java.io.Closeable")).isTrue();
		assertThat(ANALYSIS.edge("file:" + SERVICE, RelationshipType.IMPORTS,
				"file:src/main/kotlin/com/acme/model/User.kt")).isTrue();
		assertThat(ANALYSIS.edge("file:" + SERVICE, RelationshipType.IMPORTS,
				"file:src/main/kotlin/com/acme/util/Util.kt")).isTrue();
	}

	@Test
	void resolvesCallsThroughPropertiesLocalsThisInheritanceAndWildcardImportedFunctions() {
		String load = "method:com.acme.service.Service#load(Int)";
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:com.acme.repo.Repo#find(Int)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:com.acme.model.User#display()")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:com.acme.service.Service#audit(User)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:com.acme.service.BaseService#log(String)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "function:src/main/kotlin/com/acme/util/Util.kt#helper(Int)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.REFERENCES, "class:com.acme.model.User")).isTrue();
	}

	@Test
	void resolvesConstructorCallsAndCompanionFactories() {
		String main = "function:src/main/kotlin/com/acme/Main.kt#main(Repo)";
		assertThat(ANALYSIS.edge(main, RelationshipType.REFERENCES, "class:com.acme.service.Service")).isTrue();
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:com.acme.service.Service#load(Int)")).isTrue();
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:com.acme.service.Service#Service(Repo)")).isTrue();
	}

	@Test
	void derivesDependencies() {
		assertThat(ANALYSIS.edge("class:com.acme.service.Service", RelationshipType.DEPENDS_ON,
				"interface:com.acme.repo.Repo")).isTrue();
		assertThat(ANALYSIS.edge("module:com.acme.service", RelationshipType.DEPENDS_ON, "module:com.acme.model")).isTrue();
	}
}
