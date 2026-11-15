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

class CSharpExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("csharp");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void usesTheDeclaredNamespaceAsTheModule() {
		assertThat(file("Services/UserService.cs").moduleName()).isEqualTo("Acme.Services");
		assertThat(file("Models/User.cs").moduleName()).isEqualTo("Acme.Models");
	}

	@Test
	void usingDirectivesAreWildcardImportsOfNamespaces() {
		assertThat(file("Services/UserService.cs").imports()).extracting("module", "wildcard")
				.containsExactly(tuple("System", true), tuple("Acme.Models", true));
		assertThat(file("Services/IRepository.cs").imports()).extracting("module").containsExactly("Acme.Models");
	}

	@Test
	void separatesTheBaseClassFromInterfacesByNamingConvention() {
		TypeIR service = file("Services/UserService.cs").types().getFirst();

		assertThat(service.declaration()).isEqualTo(Declaration.PARTIAL);
		assertThat(service.extendsTypes()).extracting("name").containsExactly("BaseService");
		assertThat(service.implementsTypes()).extracting("name").containsExactly("IService");
		assertThat(file("Models/User.cs").types().get(1).implementsTypes()).extracting("name").containsExactly("IEntity");
	}

	@Test
	void extractsMembersPropertiesAndConstructors() {
		TypeIR user = file("Models/User.cs").types().get(1);

		assertThat(user.kind()).isEqualTo(TypeKind.CLASS);
		assertThat(user.fields()).extracting("name").containsExactly("Name");
		assertThat(user.methods()).extracting(MethodIR::name).containsExactly("User", "Display");
		assertThat(user.methods().getFirst().constructor()).isTrue();
	}

	@Test
	void mergesPartialClassesAcrossFiles() {
		assertThat(ANALYSIS.edge("class:Acme.Services.UserService", RelationshipType.CONTAINS,
				"method:Acme.Services.UserService#Load(int)")).isTrue();
		assertThat(ANALYSIS.edge("class:Acme.Services.UserService", RelationshipType.CONTAINS,
				"method:Acme.Services.UserService#Extra()")).isTrue();
	}

	@Test
	void resolvesHeritageAndNamespaceImports() {
		assertThat(ANALYSIS.edge("class:Acme.Services.UserService", RelationshipType.EXTENDS,
				"class:Acme.Services.BaseService")).isTrue();
		assertThat(ANALYSIS.edge("class:Acme.Services.UserService", RelationshipType.IMPLEMENTS,
				"interface:Acme.Services.IService")).isTrue();
		assertThat(ANALYSIS.edge("file:Services/UserService.cs", RelationshipType.IMPORTS, "file:Models/User.cs")).isTrue();
		assertThat(ANALYSIS.edge("file:Program.cs", RelationshipType.IMPORTS, "file:Services/UserService.cs")).isTrue();
	}

	@Test
	void resolvesCallsThroughFieldsLocalsThisAndInheritance() {
		String load = "method:Acme.Services.UserService#Load(int)";
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:Acme.Services.IRepository#Find(int)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:Acme.Models.User#Display()")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:Acme.Services.UserService#Audit(User)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:Acme.Services.BaseService#Log(string)")).isTrue();
	}

	@Test
	void typesVarLocalsFromObjectCreation() {
		String main = "method:Acme.Program#Main()";
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:Acme.Services.UserService#Load(int)")).isTrue();
		assertThat(ANALYSIS.edge(main, RelationshipType.CALLS, "method:Acme.Services.UserService#Extra()")).isTrue();
		assertThat(ANALYSIS.edge(main, RelationshipType.REFERENCES, "class:Acme.Services.UserService")).isTrue();
	}

	@Test
	void derivesDependencies() {
		assertThat(ANALYSIS.edge("class:Acme.Services.UserService", RelationshipType.DEPENDS_ON,
				"interface:Acme.Services.IRepository")).isTrue();
		assertThat(ANALYSIS.edge("class:Acme.Services.UserService", RelationshipType.DEPENDS_ON,
				"class:Acme.Models.User")).isTrue();
		assertThat(ANALYSIS.edge("module:Acme.Services", RelationshipType.DEPENDS_ON, "module:Acme.Models")).isTrue();
	}
}
