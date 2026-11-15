package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;

/** Vue and Svelte components, and Jupyter notebooks, in one mixed frontend and data-science repository. */
class ComponentAndNotebookExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("frontend");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void mixedRepositoriesKeepEachFilesOwnLanguage() {
		assertThat(file("src/App.vue").language()).isEqualTo(Language.VUE);
		assertThat(file("src/Counter.svelte").language()).isEqualTo(Language.SVELTE);
		assertThat(file("src/utils.ts").language()).isEqualTo(Language.TYPESCRIPT);
		assertThat(file("src/helper.js").language()).isEqualTo(Language.JAVASCRIPT);
		assertThat(file("notebooks/analysis.ipynb").language()).isEqualTo(Language.JUPYTER);
	}

	@Test
	void extractsTheScriptSetupOfAVueComponentWithTypescript() {
		FileIR app = file("src/App.vue");

		assertThat(app.moduleName()).isEqualTo("src.App");
		assertThat(app.imports()).extracting("module", "name", "alias")
				.containsExactly(tuple("./components/UserCard.vue", "default", "UserCard"), tuple("./utils", "formatName", null));
		assertThat(app.functions()).extracting(MethodIR::name).containsExactly("onSelect");
		assertThat(app.functions().getFirst().parameters().getFirst().type().name()).isEqualTo("string");
		// lines refer to the position in the .vue file, not in the script block
		assertThat(app.functions().getFirst().line()).isEqualTo(9);
	}

	@Test
	void collectsMethodsOfAnOptionsApiComponent() {
		FileIR card = file("src/components/UserCard.vue");

		assertThat(card.functions()).extracting(MethodIR::name).contains("greet");
		assertThat(file("src/Counter.svelte").functions()).extracting(MethodIR::name).containsExactly("increment");
	}

	@Test
	void linksComponentsAndScriptsThroughImportsAndCalls() {
		assertThat(ANALYSIS.edge("file:src/App.vue", RelationshipType.IMPORTS, "file:src/components/UserCard.vue")).isTrue();
		assertThat(ANALYSIS.edge("file:src/App.vue", RelationshipType.IMPORTS, "file:src/utils.ts")).isTrue();
		assertThat(ANALYSIS.edge("function:src/App.vue#onSelect(string)", RelationshipType.CALLS,
				"function:src/utils.ts#formatName(string)")).isTrue();
		assertThat(ANALYSIS.edge("file:src/Counter.svelte", RelationshipType.IMPORTS, "file:src/store.js")).isTrue();
		assertThat(ANALYSIS.edge("function:src/Counter.svelte#increment()", RelationshipType.CALLS,
				"function:src/store.js#reset()")).isTrue();
		// a default import of a function: helper()
		assertThat(ANALYSIS.edge("function:src/components/UserCard.vue#greet()", RelationshipType.CALLS,
				"function:src/helper.js#helper()")).isTrue();
	}

	@Test
	void turnsNotebookCodeCellsIntoAPythonModule() {
		FileIR notebook = file("notebooks/analysis.ipynb");

		assertThat(notebook.moduleName()).isEqualTo("notebooks.analysis");
		assertThat(notebook.imports()).extracting("module", "alias").containsExactly(tuple("pandas", "pd"), tuple("numpy", "np"));
		assertThat(notebook.functions()).extracting(MethodIR::name).containsExactly("clean", "run");
		TypeIR model = notebook.types().getFirst();
		assertThat(model.name()).isEqualTo("Model");
		assertThat(model.methods()).extracting(MethodIR::name).containsExactly("fit");
	}

	@Test
	void resolvesNotebookCallsAndExternalLibraries() {
		assertThat(ANALYSIS.edge("file:notebooks/analysis.ipynb", RelationshipType.IMPORTS, "external:module:pandas")).isTrue();
		assertThat(ANALYSIS.edge("method:notebooks.analysis.Model#fit(?)", RelationshipType.CALLS,
				"function:notebooks/analysis.ipynb#clean(?)")).isTrue();
		assertThat(ANALYSIS.edge("function:notebooks/analysis.ipynb#run()", RelationshipType.CALLS,
				"method:notebooks.analysis.Model#fit(?)")).isTrue();
		assertThat(ANALYSIS.edge("function:notebooks/analysis.ipynb#run()", RelationshipType.CALLS,
				"external:function:pandas#DataFrame")).isTrue();
	}
}
