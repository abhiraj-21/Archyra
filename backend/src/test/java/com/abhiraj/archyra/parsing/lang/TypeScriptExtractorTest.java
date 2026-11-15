package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class TypeScriptExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("typescript");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void derivesModuleFromThePathAndGroupsByDirectory() {
		FileIR service = file("src/services/user.service.ts");
		assertThat(service.moduleName()).isEqualTo("src.services.user.service");
		assertThat(service.packageName()).isEqualTo("src/services");
	}

	@Test
	void extractsEveryImportAndReExportForm() {
		assertThat(file("src/components/UserCard.tsx").imports()).extracting("module", "name", "alias")
				.contains(tuple("react", "default", "React"), tuple("react", "useState", null),
						tuple("./Avatar", "default", "Avatar"), tuple("../services", "UserService", null));
		assertThat(file("src/services/index.ts").imports()).extracting("module", "name", "wildcard")
				.contains(tuple("./user.service", null, true), tuple("./user.repository", "HttpUserRepository", false));
	}

	@Test
	void extractsInterfacesClassesAndHeritage() {
		TypeIR repository = file("src/services/user.repository.ts").types().getFirst();
		assertThat(repository.kind()).isEqualTo(TypeKind.INTERFACE);
		assertThat(repository.methods()).extracting(MethodIR::name).containsExactly("find");
		assertThat(repository.methods().getFirst().returnType().typeArguments()).containsExactly("User");

		TypeIR http = file("src/services/user.repository.ts").types().get(1);
		assertThat(http.implementsTypes()).extracting("name").containsExactly("UserRepository");
		assertThat(file("src/models/user.ts").types().get(1).implementsTypes()).extracting("name").containsExactly("User");
	}

	@Test
	void constructorParameterPropertiesBecomeTypedFields() {
		TypeIR service = file("src/services/user.service.ts").types().getFirst();

		assertThat(service.fields()).extracting("name").contains("cache", "repo");
		assertThat(service.fields().stream().filter(f -> f.name().equals("repo")).findFirst().orElseThrow().type().name())
				.isEqualTo("UserRepository");
		assertThat(service.fields().stream().filter(f -> f.name().equals("cache")).findFirst().orElseThrow().type().name())
				.isEqualTo("Map");
		MethodIR constructor = service.methods().getFirst();
		assertThat(constructor.constructor()).isTrue();
		assertThat(constructor.parameters()).extracting("name").containsExactly("repo");
	}

	@Test
	void arrowFunctionsAndFunctionDeclarationsAreFunctions() {
		assertThat(file("src/components/UserCard.tsx").functions()).extracting(MethodIR::name).containsExactly("UserCard");
		assertThat(file("src/components/Avatar.tsx").functions()).extracting(MethodIR::name).containsExactly("Avatar");
		assertThat(file("src/services/user.service.ts").functions()).extracting(MethodIR::name)
				.containsExactly("createUserService");
	}

	@Test
	void recordsCallsInstantiationsAndJsxComponents() {
		MethodIR card = file("src/components/UserCard.tsx").functions().getFirst();
		assertThat(card.calls()).extracting("receiver", "name").contains(tuple("service", "load"), tuple(null, "Avatar"),
				tuple(null, "useState"));
		MethodIR create = file("src/services/user.service.ts").functions().getFirst();
		assertThat(create.instantiatedTypes()).extracting("name").containsExactly("UserService");
	}

	// ---- resolved graph -----------------------------------------------------------------------

	@Test
	void resolvesRelativeImportsIncludingBarrelFiles() {
		String card = "file:src/components/UserCard.tsx";
		assertThat(ANALYSIS.edge(card, RelationshipType.IMPORTS, "file:src/components/Avatar.tsx")).isTrue();
		// '../services' is the barrel; UserService is re-exported from user.service.ts
		assertThat(ANALYSIS.edge(card, RelationshipType.IMPORTS, "file:src/services/user.service.ts")).isTrue();
		assertThat(ANALYSIS.edge("file:src/app.ts", RelationshipType.IMPORTS, "file:src/services/user.repository.ts")).isTrue();
	}

	@Test
	void externalPackagesBecomeExternalModules() {
		assertThat(ANALYSIS.edge("file:src/components/UserCard.tsx", RelationshipType.IMPORTS, "external:module:react")).isTrue();
		assertThat(ANALYSIS.edge("file:src/services/user.repository.ts", RelationshipType.IMPORTS,
				"external:module:@angular/common/http")).isTrue();
		assertThat(ANALYSIS.node("external:module:react").external()).isTrue();
	}

	@Test
	void resolvesInterfaceImplementationAndInjectedFields() {
		assertThat(ANALYSIS.edge("class:src.services.user.repository.HttpUserRepository", RelationshipType.IMPLEMENTS,
				"interface:src.services.user.repository.UserRepository")).isTrue();
		assertThat(ANALYSIS.edge("class:src.services.user.service.UserService", RelationshipType.DEPENDS_ON,
				"interface:src.services.user.repository.UserRepository")).isTrue();
		assertThat(ANALYSIS.edge("class:src.services.user.repository.HttpUserRepository", RelationshipType.REFERENCES,
				"external:class:@angular/common/http.HttpClient")).isTrue();
	}

	@Test
	void resolvesCallsThroughInjectedFieldsAndThis() {
		String load = "method:src.services.user.service.UserService#load(string)";
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS,
				"method:src.services.user.repository.UserRepository#find(string)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS,
				"method:src.services.user.service.UserService#audit(string)")).isTrue();
		// globals such as Map are not imported, so a call on them is unknown rather than external
		assertThat(ANALYSIS.graph.nodes()).noneMatch(node -> node.id().startsWith("external:method:Map"));
	}

	@Test
	void resolvesFunctionCallsTypedThroughReturnTypes() {
		String main = "function:src/app.ts#main(any)";
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS,
				"function:src/services/user.service.ts#createUserService(UserRepository)")).isTrue();
		// service is typed by createUserService's declared return type
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS,
				"method:src.services.user.service.UserService#load(string)")).isTrue();
	}

	@Test
	void jsxComponentsAreReferencesThroughDefaultImports() {
		assertThat(ANALYSIS.edge("function:src/components/UserCard.tsx#UserCard(?)", RelationshipType.CALLS,
				"function:src/components/Avatar.tsx#Avatar()")).isTrue();
	}

	@Test
	void derivesModuleDependenciesBetweenDirectories() {
		assertThat(ANALYSIS.edge("module:src/components", RelationshipType.DEPENDS_ON, "module:src/services")).isTrue();
		assertThat(ANALYSIS.edge("module:src/services", RelationshipType.DEPENDS_ON, "module:src/models")).isTrue();
	}
}
