package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;

class JavaScriptExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("javascript");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void extractsClassesMethodsAndEsAndCommonJsImports() {
		FileIR store = file("src/userStore.js");

		assertThat(store.imports()).extracting("module", "name", "alias").contains(tuple("./db", "Database", null),
				tuple("fs", null, "fs"));
		TypeIR type = store.types().getFirst();
		assertThat(type.qualifiedName()).isEqualTo("src.userStore.UserStore");
		assertThat(type.methods()).extracting("name").containsExactly("constructor", "find");
		assertThat(type.methods().getFirst().constructor()).isTrue();
	}

	@Test
	void typesFieldsAssignedInTheConstructorFromNewExpressions() {
		TypeIR store = file("src/userStore.js").types().getFirst();

		assertThat(store.fields()).extracting("name").contains("db");
		assertThat(store.fields().stream().filter(f -> f.name().equals("db")).findFirst().orElseThrow().type().name())
				.isEqualTo("Database");
	}

	@Test
	void resolvesCallsAndImportsAcrossFiles() {
		assertThat(ANALYSIS.edge("file:src/userStore.js", RelationshipType.IMPORTS, "file:src/db.js")).isTrue();
		assertThat(ANALYSIS.edge("file:src/userStore.js", RelationshipType.IMPORTS, "external:module:fs")).isTrue();
		assertThat(ANALYSIS.edge("file:src/index.js", RelationshipType.IMPORTS, "external:module:express")).isTrue();
		assertThat(ANALYSIS.edge("method:src.userStore.UserStore#find(?)", RelationshipType.CALLS,
				"method:src.db.Database#query(?)")).isTrue();
		assertThat(ANALYSIS.edge("method:src.userStore.UserStore#find(?)", RelationshipType.CALLS,
				"external:function:fs#readFileSync")).isTrue();
		assertThat(ANALYSIS.edge("function:src/index.js#start()", RelationshipType.CALLS,
				"method:src.userStore.UserStore#find(?)")).isTrue();
		assertThat(ANALYSIS.edge("function:src/index.js#start()", RelationshipType.REFERENCES,
				"class:src.userStore.UserStore")).isTrue();
		assertThat(ANALYSIS.edge("class:src.userStore.UserStore", RelationshipType.DEPENDS_ON, "class:src.db.Database")).isTrue();
	}

	@Test
	void capturesCommonJsAndPrototypeFunctionAssignments() {
		FileIR legacy = file("src/legacy.js");

		assertThat(legacy.functions()).extracting("name").containsExactly("load", "save", "handle", "prepare");
		// calls inside them resolve to the function declared in the same file
		assertThat(ANALYSIS.edge("function:src/legacy.js#load(?)", RelationshipType.CALLS,
				"function:src/legacy.js#prepare(?)")).isTrue();
		assertThat(ANALYSIS.edge("function:src/legacy.js#save(?)", RelationshipType.CALLS,
				"function:src/legacy.js#prepare(?)")).isTrue();
	}
}
