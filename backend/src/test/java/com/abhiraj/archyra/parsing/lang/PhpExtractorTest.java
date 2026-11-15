package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class PhpExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("php");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void normalisesNamespacesAndUseStatements() {
		FileIR service = file("src/Services/UserService.php");

		assertThat(service.moduleName()).isEqualTo("App.Services");
		assertThat(service.imports()).extracting("module", "name")
				.containsExactly(tuple("App.Models", "User"), tuple("App.Contracts", "Repo"),
						tuple("App.Helpers", "log_message"));
	}

	@Test
	void extractsClassHeritageTraitUseAndPromotedProperties() {
		TypeIR service = file("src/Services/UserService.php").types().getFirst();

		assertThat(service.extendsTypes()).extracting("name").containsExactly("BaseService");
		assertThat(service.implementsTypes()).extracting("name").containsExactly("Repo", "Loggable");
		assertThat(service.fields()).extracting("name").contains("repo", "source");
		assertThat(service.fields().stream().filter(f -> f.name().equals("source")).findFirst().orElseThrow().type().name())
				.isEqualTo("Repo");
		assertThat(service.methods()).extracting(MethodIR::name).contains("__construct", "load", "audit", "helper");
		assertThat(file("src/Services/Loggable.php").types().getFirst().kind()).isEqualTo(TypeKind.TRAIT);
	}

	@Test
	void normalisesThisAndStaticReceivers() {
		TypeIR service = file("src/Services/UserService.php").types().getFirst();
		MethodIR load = service.methods().stream().filter(m -> m.name().equals("load")).findFirst().orElseThrow();

		assertThat(load.calls()).extracting("receiver", "name").contains(tuple("this.repo", "find"),
				tuple("created", "display"), tuple("this", "audit"), tuple("this", "helper"), tuple(null, "log_message"));
	}

	// ---- resolved graph -----------------------------------------------------------------------

	@Test
	void resolvesHeritageTraitsAndImports() {
		String service = "class:App.Services.UserService";
		assertThat(ANALYSIS.edge(service, RelationshipType.EXTENDS, "class:App.Services.BaseService")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPLEMENTS, "interface:App.Contracts.Repo")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPLEMENTS, "interface:App.Services.Loggable")).isTrue();
		assertThat(ANALYSIS.edge("file:src/Services/UserService.php", RelationshipType.IMPORTS, "file:src/Models/User.php")).isTrue();
		assertThat(ANALYSIS.edge("file:src/Services/UserService.php", RelationshipType.IMPORTS, "file:src/helpers.php")).isTrue();
	}

	@Test
	void resolvesCallsThroughPromotedFieldsThisSelfInheritanceAndImportedFunctions() {
		String load = "method:App.Services.UserService#load(int)";
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Contracts.Repo#find(int)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Models.User#display()")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Services.UserService#audit(User)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Services.BaseService#boot()")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Services.Loggable#record(?)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Services.UserService#helper(?)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "function:src/helpers.php#log_message(?)")).isTrue();
	}

	@Test
	void derivesDependencies() {
		assertThat(ANALYSIS.edge("class:App.Services.UserService", RelationshipType.DEPENDS_ON,
				"interface:App.Contracts.Repo")).isTrue();
		assertThat(ANALYSIS.edge("class:App.Services.UserService", RelationshipType.DEPENDS_ON,
				"class:App.Models.User")).isTrue();
	}
}
