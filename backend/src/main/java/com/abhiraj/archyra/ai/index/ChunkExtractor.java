package com.abhiraj.archyra.ai.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.common.TestPathClassifier;
import com.abhiraj.archyra.ir.CodeGraphIR;
import com.abhiraj.archyra.ir.EdgeIR;
import com.abhiraj.archyra.ir.NodeIR;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.parsing.lang.NotebookExtractor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cuts the working code of a cloned repository into chunks, using the line ranges of the resolved graph: one chunk per
 * method or function, and one header chunk per type. Test code and external nodes are never chunked. Never logs chunk
 * text.
 *
 * <p>A type's header runs from its first line to the line before its first member or nested type (at most
 * {@value #MAX_HEADER_LINES} lines), so it holds the signature, annotations and field declarations. A type without
 * members (an enum, a record, an interface or a struct with only fields) gets its whole declaration. A text longer than
 * {@code archyra.ai.chunk.max-chars} keeps its head and ends with a marker. Beyond
 * {@code archyra.ai.chunk.max-per-analysis} the first chunks in file order are kept and the result says it was capped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkExtractor {

	static final int MAX_HEADER_LINES = 80;
	static final String TRUNCATION_MARKER = "\n... [truncated]";

	private static final Set<NodeType> CHUNKED = Set.of(NodeType.CLASS, NodeType.INTERFACE, NodeType.METHOD, NodeType.FUNCTION);

	private final AiProperties properties;

	/** @param root the root of the clone the paths of {@code graph} are relative to */
	public ChunkSet extract(CodeGraphIR graph, Path root) {
		int max = properties.chunk().maxPerAnalysis();
		int maxChars = properties.chunk().maxChars();

		Map<String, List<NodeIR>> membersOf = new HashMap<>();
		Map<String, NodeIR> byId = new HashMap<>();
		graph.nodes().forEach(node -> byId.put(node.id(), node));
		for (EdgeIR edge : graph.edges()) {
			if (edge.type() == RelationshipType.CONTAINS && byId.containsKey(edge.target())) {
				membersOf.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(byId.get(edge.target()));
			}
		}

		Map<String, List<NodeIR>> byFile = new LinkedHashMap<>();
		graph.nodes().stream()
				.filter(node -> !node.external() && CHUNKED.contains(node.type()) && node.file() != null
						&& node.line() != null && node.endLine() != null && !TestPathClassifier.isTestPath(node.file()))
				.sorted(Comparator.comparing(NodeIR::file).thenComparing(NodeIR::line).thenComparing(NodeIR::id))
				.forEach(node -> byFile.computeIfAbsent(node.file(), key -> new ArrayList<>()).add(node));

		List<ChunkData> chunks = new ArrayList<>();
		boolean capped = false;
		files:
		for (Map.Entry<String, List<NodeIR>> entry : byFile.entrySet()) {
			List<String> lines = readLines(root, entry.getKey());
			if (lines == null) {
				continue;
			}
			for (NodeIR node : entry.getValue()) {
				ChunkData chunk = chunkOf(node, lines, membersOf.getOrDefault(node.id(), List.of()), maxChars);
				if (chunk == null) {
					continue;
				}
				if (chunks.size() >= max) {
					capped = true;
					break files;
				}
				chunks.add(chunk);
			}
		}
		return new ChunkSet(chunks, capped);
	}

	private static ChunkData chunkOf(NodeIR node, List<String> lines, List<NodeIR> members, int maxChars) {
		int start = node.line();
		int end = node.endLine();
		ChunkKind kind;
		if (node.type() == NodeType.CLASS || node.type() == NodeType.INTERFACE) {
			kind = ChunkKind.TYPE_HEADER;
			int firstMember = members.stream()
					.filter(member -> node.file().equals(member.file()) && member.line() != null && member.line() >= start)
					.mapToInt(NodeIR::line).min().orElse(Integer.MAX_VALUE);
			if (firstMember != Integer.MAX_VALUE) {
				if (firstMember <= start) {
					return null; // a stub made of receiver methods (Go, Rust): the methods carry the code
				}
				end = Math.min(firstMember - 1, start + MAX_HEADER_LINES - 1);
			}
		}
		else {
			kind = node.type() == NodeType.METHOD ? ChunkKind.METHOD : ChunkKind.FUNCTION;
		}

		end = Math.min(end, lines.size());
		if (start < 1 || start > end) {
			return null;
		}
		while (end > start && lines.get(end - 1).isBlank()) {
			end--; // the stored range is the text's range: a header often ends at the blank line before its first member
		}
		String text = String.join("\n", lines.subList(start - 1, end)).strip();
		if (text.isEmpty()) {
			return null;
		}
		if (text.length() > maxChars) {
			text = text.substring(0, maxChars) + TRUNCATION_MARKER;
		}
		return new ChunkData(node.id(), kind, node.file(), start, end, text);
	}

	/** The lines of a source file (a notebook as its joined code), or null if it cannot be read: such a file is skipped. */
	private static List<String> readLines(Path root, String relativePath) {
		try {
			Path file = root.resolve(relativePath).normalize();
			if (!file.startsWith(root.normalize())) {
				return null;
			}
			String text = relativePath.endsWith(".ipynb")
					? NotebookExtractor.joinedCode(file)
					: new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			text = text.replace("\u0000", ""); // PostgreSQL text cannot hold NUL
			List<String> lines = new ArrayList<>();
			for (String line : text.split("\n", -1)) {
				lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
			}
			return lines;
		}
		catch (IOException | RuntimeException ex) {
			log.debug("Skipping a file that could not be read for chunking ({})", ex.getClass().getSimpleName());
			return null;
		}
	}
}
