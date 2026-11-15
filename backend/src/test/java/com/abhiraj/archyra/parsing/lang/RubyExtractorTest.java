package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class RubyExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("ruby");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void qualifiesTypesByLexicalNestingBecauseConstantsAreGlobal() {
		FileIR service = file("lib/services/user_service.rb");

		assertThat(service.moduleName()).isEmpty();
		TypeIR app = service.types().getFirst();
		assertThat(app.kind()).isEqualTo(TypeKind.MODULE);
		TypeIR userService = app.nestedTypes().getFirst().nestedTypes().getFirst();
		assertThat(userService.qualifiedName()).isEqualTo("App.Services.UserService");
		assertThat(userService.extendsTypes()).extracting("name").containsExactly("BaseService");
		assertThat(userService.implementsTypes()).extracting("name").containsExactly("Loggable");
	}

	@Test
	void extractsRequiresAndMethods() {
		FileIR service = file("lib/services/user_service.rb");

		assertThat(service.imports()).extracting("module", "relativeLevel").containsExactly(tuple("json", 0),
				tuple("../models/user", 1), tuple("../cache", 1), tuple("base_service", 1), tuple("loggable", 1));
		TypeIR userService = service.types().getFirst().nestedTypes().getFirst().nestedTypes().getFirst();
		assertThat(userService.methods()).extracting(MethodIR::name).containsExactly("initialize", "load", "audit");
		assertThat(userService.methods().getFirst().constructor()).isTrue();
		assertThat(userService.fields()).extracting("name").containsExactly("repo", "cache");
		assertThat(userService.fields().get(1).type().name()).isEqualTo("Cache");
	}

	@Test
	void turnsNewIntoAnInstantiationCallAndNormalisesReceivers() {
		TypeIR userService = file("lib/services/user_service.rb").types().getFirst().nestedTypes().getFirst()
				.nestedTypes().getFirst();
		MethodIR load = userService.methods().get(1);

		assertThat(load.calls()).extracting("receiver", "name").contains(tuple("this.repo", "find"),
				tuple(null, "Models.User"), tuple("created", "display"), tuple(null, "audit"), tuple("this", "audit"),
				tuple("this.cache", "get"), tuple("JSON", "generate"));
		assertThat(file("app.rb").functions()).extracting(MethodIR::name).containsExactly("run");
	}

	@Test
	void resolvesRequiresToFilesAndGemsToExternalModules() {
		String service = "file:lib/services/user_service.rb";
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "file:lib/models/user.rb")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "file:lib/cache.rb")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "file:lib/services/base_service.rb")).isTrue();
		assertThat(ANALYSIS.edge(service, RelationshipType.IMPORTS, "external:module:json")).isTrue();
		assertThat(ANALYSIS.edge("file:app.rb", RelationshipType.IMPORTS, service)).isTrue();
	}

	@Test
	void resolvesHeritageMixinsAndNestedConstants() {
		String userService = "class:App.Services.UserService";
		assertThat(ANALYSIS.edge(userService, RelationshipType.EXTENDS, "class:BaseService")).isTrue();
		assertThat(ANALYSIS.edge(userService, RelationshipType.IMPLEMENTS, "class:Loggable")).isTrue();
		assertThat(ANALYSIS.node("class:Loggable").kind()).isEqualTo("MODULE");
		assertThat(ANALYSIS.edge("class:App.Services", RelationshipType.CONTAINS, userService)).isTrue();
	}

	@Test
	void resolvesCallsInstantiationsAndInheritedMethods() {
		String load = "method:App.Services.UserService#load(?)";
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Models.User#initialize(?)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.REFERENCES, "class:App.Models.User")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Models.User#display()")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:App.Services.UserService#audit(?)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:BaseService#log(?)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:Loggable#record(?)")).isTrue();
		assertThat(ANALYSIS.edge(load, RelationshipType.CALLS, "method:Cache#get(?)")).isTrue();
		assertThat(ANALYSIS.edge("function:app.rb#run()", RelationshipType.CALLS, "method:App.Services.UserService#load(?)")).isTrue();
	}

	@Test
	void derivesDependencies() {
		assertThat(ANALYSIS.edge("class:App.Services.UserService", RelationshipType.DEPENDS_ON, "class:Cache")).isTrue();
		assertThat(ANALYSIS.edge("class:App.Services.UserService", RelationshipType.DEPENDS_ON, "class:App.Models.User")).isTrue();
	}
}
