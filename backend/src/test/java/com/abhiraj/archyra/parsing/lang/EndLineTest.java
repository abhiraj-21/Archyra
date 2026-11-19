package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.Declaration;
import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.TypeIR;

/**
 * Every declaration the real parser produces for every language fixture has a sensible 1-based, inclusive range:
 * it never ends before it starts, never runs past the end of its file, and pieces of one type keep their own range.
 */
class EndLineTest {

	@ParameterizedTest
	@ValueSource(strings = { "csharp", "frontend", "go", "javascript", "kotlin", "php", "python", "ruby", "rust",
			"solidity", "typescript" })
	void everyTypeAndMethodHasAnEndLineWithinItsFile(String fixture) throws IOException {
		FixtureAnalysis analysis = FixtureAnalysis.of(fixture);
		int declarations = 0;
		for (FileIR file : analysis.repository().files()) {
			int fileLines = Files.readAllLines(analysis.source(file.path()).absolutePath()).size();
			for (Range range : ranges(file)) {
				declarations++;
				assertThat(range.endLine()).as("%s:%s end", file.path(), range.name()).isGreaterThanOrEqualTo(range.line());
				// a notebook's line numbers refer to its joined code, not to the JSON file
				if (!file.path().endsWith(".ipynb")) {
					assertThat(range.endLine()).as("%s:%s within file", file.path(), range.name())
							.isLessThanOrEqualTo(fileLines);
				}
			}
		}
		assertThat(declarations).as("declarations in the " + fixture + " fixture").isPositive();
	}

	@ParameterizedTest
	@ValueSource(strings = { "csharp", "go", "javascript", "kotlin", "php", "python", "ruby", "rust", "solidity",
			"typescript" })
	void everyDeclaredNodeOfTheResolvedGraphCarriesItsRange(String fixture) {
		FixtureAnalysis analysis = FixtureAnalysis.of(fixture);
		List<NodeIR> declared = analysis.graph.nodes().stream()
				.filter(node -> !node.external() && Set.of(NodeType.CLASS, NodeType.INTERFACE, NodeType.METHOD,
						NodeType.FUNCTION).contains(node.type()))
				.toList();

		assertThat(declared).isNotEmpty().allSatisfy(node -> {
			assertThat(node.line()).as(node.id()).isNotNull();
			assertThat(node.endLine()).as(node.id()).isNotNull().isGreaterThanOrEqualTo(node.line());
		});
		assertThat(analysis.graph.nodes()).filteredOn(NodeIR::external)
				.allSatisfy(node -> assertThat(node.endLine()).as(node.id()).isNull());
	}

	@Test
	void aMethodRangeCoversItsWholeBody() throws IOException {
		FixtureAnalysis analysis = FixtureAnalysis.of("go");
		FileIR file = analysis.repository().files().stream()
				.filter(f -> f.path().equals("internal/store/store.go")).findFirst().orElseThrow();
		List<String> lines = Files.readAllLines(analysis.source(file.path()).absolutePath());

		for (TypeIR type : file.types()) {
			for (MethodIR method : type.methods()) {
				assertThat(method.endLine()).isGreaterThanOrEqualTo(method.line());
				assertThat(lines.get(method.line() - 1)).contains(method.name());
			}
		}
	}

	@Test
	void piecesOfAPartialTypeKeepTheirOwnRanges() {
		FixtureAnalysis analysis = FixtureAnalysis.of("csharp");
		List<TypeIR> pieces = analysis.repository().files().stream().flatMap(file -> file.types().stream())
				.filter(type -> type.qualifiedName().endsWith("UserService"))
				.toList();

		assertThat(pieces).hasSizeGreaterThanOrEqualTo(2);
		assertThat(pieces).extracting(TypeIR::declaration).contains(Declaration.PARTIAL);
		assertThat(pieces).allSatisfy(piece -> assertThat(piece.endLine()).isGreaterThan(piece.line()));
	}

	@Test
	void anExtensionTypeEndsWhereItsLastReceiverMethodEnds() {
		FileIR server = FixtureAnalysis.file("go", "internal/store/server.go");
		TypeIR extension = server.types().getFirst();

		assertThat(extension.declaration()).isEqualTo(Declaration.EXTENSION);
		assertThat(extension.endLine()).isEqualTo(
				extension.methods().stream().mapToInt(MethodIR::endLine).max().orElseThrow());
		assertThat(extension.line()).isEqualTo(extension.methods().getFirst().line());
	}

	@Test
	void vueScriptBlocksKeepOriginalLineNumbers() throws IOException {
		FixtureAnalysis analysis = FixtureAnalysis.of("frontend");
		FileIR app = analysis.repository().files().stream()
				.filter(file -> file.path().equals("src/App.vue")).findFirst().orElseThrow();
		List<String> lines = Files.readAllLines(analysis.source(app.path()).absolutePath());

		List<Range> ranges = ranges(app);
		assertThat(ranges).isNotEmpty().allSatisfy(range -> {
			assertThat(range.endLine()).isLessThanOrEqualTo(lines.size());
			assertThat(lines.get(range.line() - 1)).as("declaration line of %s", range.name()).contains(range.name());
		});
	}

	private record Range(String name, int line, int endLine) {
	}

	private static List<Range> ranges(FileIR file) {
		List<Range> out = new ArrayList<>();
		file.functions().forEach(function -> out.add(new Range(function.name(), function.line(), function.endLine())));
		file.types().forEach(type -> collect(type, out));
		return out;
	}

	private static void collect(TypeIR type, List<Range> out) {
		out.add(new Range(type.name(), type.line(), type.endLine()));
		type.methods().forEach(method -> out.add(new Range(method.name(), method.line(), method.endLine())));
		type.nestedTypes().forEach(nested -> collect(nested, out));
	}
}
