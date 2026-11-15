package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class PythonExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("python");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void derivesModuleAndPackageFromThePath() {
		FileIR service = file("app/services/user_service.py");
		assertThat(service.moduleName()).isEqualTo("app.services.user_service");
		assertThat(service.packageName()).isEqualTo("app.services");
		assertThat(file("app/models/__init__.py").moduleName()).isEqualTo("app.models");
		assertThat(file("app/models/__init__.py").packageName()).isEqualTo("app.models");
		assertThat(file("main.py").moduleName()).isEqualTo("main");
		assertThat(file("main.py").packageName()).isEmpty();
	}

	@Test
	void extractsEveryImportForm() {
		FileIR service = file("app/services/user_service.py");

		assertThat(service.imports()).extracting("module", "name", "alias", "relativeLevel", "wildcard")
				.contains(
						org.assertj.core.groups.Tuple.tuple("os", null, null, 0, false),
						org.assertj.core.groups.Tuple.tuple("numpy", null, "np", 0, false),
						org.assertj.core.groups.Tuple.tuple("app.models", "User", null, 0, false),
						org.assertj.core.groups.Tuple.tuple("repository", "UserRepository", null, 1, false),
						org.assertj.core.groups.Tuple.tuple("", "helpers", null, 1, false));
	}

	@Test
	void extractsClassesWithBasesFieldsAndMethodsWithoutSelf() {
		TypeIR service = file("app/services/user_service.py").types().getFirst();

		assertThat(service.kind()).isEqualTo(TypeKind.CLASS);
		assertThat(service.qualifiedName()).isEqualTo("app.services.user_service.UserService");
		assertThat(service.methods()).extracting(MethodIR::name).containsExactly("__init__", "register", "audit");
		MethodIR constructor = service.methods().getFirst();
		assertThat(constructor.constructor()).isTrue();
		assertThat(constructor.parameters()).extracting("name").containsExactly("repo", "name");

		TypeIR memory = file("app/services/repository.py").types().get(1);
		assertThat(memory.extendsTypes()).extracting("name").containsExactly("UserRepository");
		TypeIR repository = file("app/services/repository.py").types().getFirst();
		assertThat(repository.extendsTypes()).extracting("name").containsExactly("ABC");
	}

	@Test
	void infersFieldTypesFromAnnotationsParametersAndConstructorCalls() {
		TypeIR service = file("app/services/user_service.py").types().getFirst();

		assertThat(service.fields()).extracting("name").contains("limit", "repo", "fallback", "name");
		assertThat(service.fields().stream().filter(f -> f.name().equals("repo")).findFirst().orElseThrow().type().name())
				.isEqualTo("UserRepository");
		assertThat(service.fields().stream().filter(f -> f.name().equals("fallback")).findFirst().orElseThrow().type().name())
				.isEqualTo("MemoryRepository");
	}

	@Test
	void recordsFreeFunctionsAndNormalisesSelfReceivers() {
		FileIR service = file("app/services/user_service.py");

		assertThat(service.functions()).extracting(MethodIR::name).containsExactly("create_service");
		MethodIR register = service.types().getFirst().methods().get(1);
		assertThat(register.calls()).extracting("receiver", "name").contains(
				org.assertj.core.groups.Tuple.tuple("user", "display"),
				org.assertj.core.groups.Tuple.tuple("this", "audit"),
				org.assertj.core.groups.Tuple.tuple("helpers", "log"),
				org.assertj.core.groups.Tuple.tuple("np", "array"),
				org.assertj.core.groups.Tuple.tuple("this.repo", "save"));
		assertThat(register.calls()).extracting("name").contains("User");
	}

	// ---- resolved graph -----------------------------------------------------------------------

	@Test
	void linksInheritanceAcrossModules() {
		assertThat(ANALYSIS.edge("class:app.services.repository.MemoryRepository", RelationshipType.EXTENDS,
				"class:app.services.repository.UserRepository")).isTrue();
		assertThat(ANALYSIS.edge("class:app.services.repository.UserRepository", RelationshipType.EXTENDS,
				"external:class:abc.ABC")).isTrue();
	}

	@Test
	void resolvesImportsToFilesIncludingRelativeAndReexported() {
		String service = "file:app/services/user_service.py";
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "file:app/services/repository.py")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "file:app/services/helpers.py")).isTrue();
		// `from app.models import User` is re-exported by app/models/__init__.py: the edge goes to the declaring file
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "file:app/models/user.py")).isTrue();
		assertThat(ANALYSIS.edge("file:main.py", RelationshipType.IMPORTS, service)).isTrue();
	}

	@Test
	void externalPackagesBecomeExternalModules() {
		String service = "file:app/services/user_service.py";
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "external:module:numpy")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "external:module:os")).isTrue();
		assertThat(ANALYSIS.node("external:module:numpy").external()).isTrue();
		assertThat(ANALYSIS.edge("file:app/models/user.py", RelationshipType.IMPORTS, "external:module:dataclasses")).isTrue();
	}

	@Test
	void resolvesCallsThroughFieldsLocalsModulesAndConstructors() {
		String register = "method:app.services.user_service.UserService#register(str)";
		assertThat(ANALYSIS.edge(register, RelationshipType.CALLS, "method:app.services.repository.UserRepository#save(User)")).isTrue();
		assertThat(ANALYSIS.edge(register, RelationshipType.CALLS, "method:app.models.user.User#display()")).isTrue();
		assertThat(ANALYSIS.edge(register, RelationshipType.CALLS, "method:app.services.user_service.UserService#audit(?)")).isTrue();
		assertThat(ANALYSIS.edge(register, RelationshipType.CALLS, "function:app/services/helpers.py#log(?)")).isTrue();
		assertThat(ANALYSIS.edge(register, RelationshipType.CALLS, "external:function:numpy#array")).isTrue();
		assertThat(ANALYSIS.edge(register, RelationshipType.REFERENCES, "class:app.models.user.User")).isTrue();
	}

	@Test
	void resolvesFunctionCallsAcrossFilesAndInstantiations() {
		assertThat(ANALYSIS.edge("function:main.py#main()", RelationshipType.CALLS,
				"function:app/services/user_service.py#create_service()")).isTrue();
		assertThat(ANALYSIS.edge("function:main.py#main()", RelationshipType.CALLS,
				"method:app.services.user_service.UserService#register(str)")).isTrue();
		assertThat(ANALYSIS.edge("function:main.py#main()", RelationshipType.REFERENCES,
				"class:app.services.user_service.UserService")).isTrue();
		assertThat(ANALYSIS.edge("function:app/services/user_service.py#create_service()", RelationshipType.CALLS,
				"method:app.services.user_service.UserService#__init__(UserRepository,str)")).isTrue();
	}

	@Test
	void derivesClassAndModuleDependencies() {
		assertThat(ANALYSIS.edge("class:app.services.user_service.UserService", RelationshipType.DEPENDS_ON,
				"class:app.models.user.User")).isTrue();
		assertThat(ANALYSIS.edge("class:app.services.user_service.UserService", RelationshipType.DEPENDS_ON,
				"class:app.services.repository.UserRepository")).isTrue();
		assertThat(ANALYSIS.edge("module:app.services", RelationshipType.DEPENDS_ON, "module:app.models")).isTrue();
	}

	@Test
	void containmentUsesPackagesAsModules() {
		assertThat(ANALYSIS.edge("module:app.services", RelationshipType.CONTAINS, "file:app/services/helpers.py")).isTrue();
		assertThat(ANALYSIS.edge("file:app/services/helpers.py", RelationshipType.CONTAINS,
				"function:app/services/helpers.py#log(?)")).isTrue();
	}
}
