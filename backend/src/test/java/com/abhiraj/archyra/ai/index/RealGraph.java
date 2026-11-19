package com.abhiraj.archyra.ai.index;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.abhiraj.archyra.ingestion.DiscoveredFiles;
import com.abhiraj.archyra.ingestion.FileDiscovery;
import com.abhiraj.archyra.ingestion.IngestionProperties;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.parsing.NativeLibraries;
import com.abhiraj.archyra.parsing.ParseResult;
import com.abhiraj.archyra.parsing.ParsingService;
import com.abhiraj.archyra.parsing.ReferenceResolver;
import com.abhiraj.archyra.parsing.lang.GoExtractor;
import com.abhiraj.archyra.parsing.lang.GoModuleResolver;
import com.abhiraj.archyra.parsing.lang.JavaExtractor;
import com.abhiraj.archyra.parsing.lang.JavaScriptExtractor;
import com.abhiraj.archyra.parsing.lang.NamespaceModuleResolver;
import com.abhiraj.archyra.parsing.lang.NotebookExtractor;
import com.abhiraj.archyra.parsing.lang.PythonExtractor;
import com.abhiraj.archyra.parsing.lang.PythonModuleResolver;
import com.abhiraj.archyra.parsing.lang.TypeScriptExtractor;
import com.abhiraj.archyra.parsing.lang.JavaScriptModuleResolver;
import com.abhiraj.archyra.parsing.lang.SingleFileComponentExtractor;

/** Test support: the real discovery, parser and resolver over a directory, as the pipeline runs them, without Spring. */
final class RealGraph {

	final Path root;
	final CodeGraphIR graph;

	private RealGraph(Path root, CodeGraphIR graph) {
		this.root = root;
		this.graph = graph;
	}

	static RealGraph of(Path root) {
		NativeLibraries.configure(Path.of("native"));
		DiscoveredFiles discovered = new FileDiscovery(
				new IngestionProperties(10_000, 200_000_000, 1_000_000, Duration.ofMinutes(1))).discover(root);
		PythonExtractor python = new PythonExtractor();
		JavaScriptExtractor javaScript = new JavaScriptExtractor();
		TypeScriptExtractor typeScript = new TypeScriptExtractor();
		ParsingService parsing = new ParsingService(List.of(new JavaExtractor(), python, javaScript, typeScript,
				new GoExtractor(), new SingleFileComponentExtractor(javaScript, typeScript), new NotebookExtractor(python)));
		ParseResult parsed = parsing.parse("repo", "https://github.com/example/repo", discovered.sourceFiles());
		CodeGraphIR graph = new ReferenceResolver(List.of(new NamespaceModuleResolver(), new PythonModuleResolver(),
				new JavaScriptModuleResolver(), new GoModuleResolver())).resolve(parsed.repository());
		return new RealGraph(root, graph);
	}

	NodeIR node(String id) {
		return graph.nodes().stream().filter(node -> node.id().equals(id)).findFirst()
				.orElseThrow(() -> new AssertionError("No node " + id));
	}
}
