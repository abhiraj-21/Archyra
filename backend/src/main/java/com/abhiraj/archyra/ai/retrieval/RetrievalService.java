package com.abhiraj.archyra.ai.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.index.ChunkSearch;
import com.abhiraj.archyra.ai.index.EmbeddingService;
import com.abhiraj.archyra.ai.index.FoundChunk;
import com.abhiraj.archyra.graph.NeighborEdge;
import com.abhiraj.archyra.graph.Neighborhood;
import com.abhiraj.archyra.graph.NodeInfo;
import com.abhiraj.archyra.graph.RetrievalGraphService;
import com.abhiraj.archyra.ir.NodeType;

import lombok.RequiredArgsConstructor;

/**
 * Hybrid retrieval: finds what to show the model for a question about one analysis.
 *
 * <ol>
 *   <li><b>Name lookup</b>: identifiers in the question are looked up in the graph (highest priority). This is what makes
 *       "what depends on {@code PaymentService}?" work even when no vector is close.</li>
 *   <li><b>Vector search</b>: the question is embedded locally and the nearest chunks of the analysis are taken, those
 *       below {@code min-similarity} dropped. A question of fewer than {@value #SHORT_QUESTION_WORDS} words is joined with
 *       the previous question first ("explain that class").</li>
 *   <li><b>Sticky seeds</b>: the nodes the previous answer cited.</li>
 *   <li><b>Graph expansion</b>: from the named nodes, the best {@code vector-seeds-expanded} vector hits and the sticky nodes, one hop through DEPENDS_ON, CALLS, EXTENDS and IMPLEMENTS in both directions from every
 *       seed (a method also expands from its type). Each edge is a fact that names both ends.</li>
 * </ol>
 *
 * The result is ranked: name matches, facts, vector hits, neighbours, sticky items; see {@link RetrievedContext#within}
 * for fitting it to a provider's budget. Test code never appears, and the result is deterministic for the same input
 * (every read has a stable order). Never logs the question or code.
 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

	static final int SHORT_QUESTION_WORDS = 6;
	/** A type that is named brings this many of its methods along with its header. */
	static final int METHODS_PER_NAMED_TYPE = 4;

	private final EmbeddingService embeddings;
	private final ChunkSearch chunks;
	private final RetrievalGraphService graph;
	private final AiProperties properties;

	public RetrievedContext retrieve(RetrievalRequest request) {
		UUID analysisId = request.analysisId();
		String key = analysisId.toString();
		AiProperties.Retrieval settings = properties.retrieval();

		// 1. what the question names
		List<NodeInfo> named = graph.findByNames(key, QuestionTerms.extract(request.message()), settings.nameLookupMaxNodes());
		List<CodeItem> nameItems = nameMatchedCode(analysisId, key, named);

		// 2. what is close to the question
		float[] queryVector = embeddings.embed(queryText(request));
		List<FoundChunk> vectorHits = chunks.nearest(analysisId, queryVector, settings.vectorTopK()).stream()
				.filter(hit -> hit.similarity() >= settings.minSimilarity()).toList();

		// 3. seeds: named, then close, then cited before; a seed's type is a seed too
		Set<String> lookedUp = new LinkedHashSet<>();
		Set<String> expandable = new LinkedHashSet<>();
		named.forEach(node -> expandable.add(node.id()));
		vectorHits.forEach(hit -> lookedUp.add(hit.nodeId()));
		vectorHits.stream().map(FoundChunk::nodeId).distinct().limit(settings.vectorSeedsExpanded()).forEach(expandable::add);
		expandable.addAll(request.stickyNodeIds());
		lookedUp.addAll(expandable);
		Map<String, NodeInfo> known = new LinkedHashMap<>(graph.nodesById(key, lookedUp));
		named.forEach(node -> known.put(node.id(), node));

		// Only the named nodes, the best few vector hits and the cited nodes are expanded into facts; the other hits still
		// bring their own code. Facts rank above chunks, so expanding every hit could fill a small budget with facts alone.
		Set<String> seeds = new LinkedHashSet<>();
		for (String id : expandable) {
			NodeInfo node = known.get(id);
			if (node == null) {
				continue; // a sticky node that is gone, or a hit on a node the graph no longer has
			}
			seeds.add(id);
			if (node.type() == NodeType.METHOD && node.parentId() != null) {
				seeds.add(node.parentId());
			}
		}
		Neighborhood neighborhood = graph.neighbors(key, seeds, settings.neighborsPerNode());
		known.putAll(graph.nodesById(key, seeds.stream().filter(id -> !known.containsKey(id)).toList()));

		// 4. facts, in the order of their seeds
		List<String> seedOrder = List.copyOf(seeds);
		List<NeighborEdge> orderedEdges = neighborhood.edges().stream()
				.sorted(Comparator.comparingInt(edge -> seedOrder.indexOf(edge.seedId()))).toList();
		List<FactItem> facts = facts(orderedEdges, named, neighborhood, known);

		// 5. assemble by priority, one entry per chunk
		List<ContextItem> items = new ArrayList<>(nameItems);
		items.addAll(facts);
		Set<String> seenChunks = nameItems.stream().map(CodeItem::key).collect(Collectors.toCollection(LinkedHashSet::new));

		for (FoundChunk hit : vectorHits) {
			CodeItem item = code(Priority.VECTOR, hit, known.get(hit.nodeId()), hit.similarity());
			if (seenChunks.add(item.key())) {
				items.add(item);
			}
		}

		List<String> neighborIds = orderedEdges.stream().map(edge -> edge.other().id()).distinct().toList();
		Map<String, List<FoundChunk>> neighborChunks = chunks.forNodes(analysisId, neighborIds);
		for (NeighborEdge edge : orderedEdges) {
			for (FoundChunk chunk : first(neighborChunks.get(edge.other().id()))) {
				CodeItem item = code(Priority.NEIGHBOR, chunk, edge.other(), 0);
				if (seenChunks.add(item.key())) {
					items.add(item);
				}
			}
		}

		Map<String, List<FoundChunk>> stickyChunks = chunks.forNodes(analysisId,
				request.stickyNodeIds().stream().filter(known::containsKey).toList());
		for (String id : request.stickyNodeIds()) {
			for (FoundChunk chunk : first(stickyChunks.get(id))) {
				CodeItem item = code(Priority.STICKY, chunk, known.get(id), 0);
				if (seenChunks.add(item.key())) {
					items.add(item);
				}
			}
		}
		return new RetrievedContext(items);
	}

	/** The text the question is embedded as: a short question is joined with the one before it. */
	static String queryText(RetrievalRequest request) {
		String message = request.message().strip();
		int words = message.isEmpty() ? 0 : message.split("\\s+").length;
		if (words < SHORT_QUESTION_WORDS && request.previousQuestion() != null && !request.previousQuestion().isBlank()) {
			return request.previousQuestion().strip() + " " + message;
		}
		return message;
	}

	// ---- name matches --------------------------------------------------------------------------

	/**
	 * The code of the named nodes: a type's header and up to {@value #METHODS_PER_NAMED_TYPE} of its methods, a method's or
	 * function's own chunk, the top-level declarations of a file.
	 */
	private List<CodeItem> nameMatchedCode(UUID analysisId, String key, List<NodeInfo> named) {
		Map<String, List<NodeInfo>> membersOfNode = new LinkedHashMap<>();
		List<String> wanted = new ArrayList<>();
		for (NodeInfo node : named) {
			wanted.add(node.id());
			List<NodeInfo> members = switch (node.type()) {
				case CLASS, INTERFACE -> graph.membersOf(key, node.id(), Set.of(NodeType.METHOD), METHODS_PER_NAMED_TYPE);
				case FILE -> graph.membersOf(key, node.id(), Set.of(NodeType.CLASS, NodeType.INTERFACE, NodeType.FUNCTION),
						properties.retrieval().nameLookupMaxNodes());
				default -> List.<NodeInfo>of();
			};
			membersOfNode.put(node.id(), members);
			members.forEach(member -> wanted.add(member.id()));
		}
		Map<String, List<FoundChunk>> found = chunks.forNodes(analysisId, wanted);

		List<CodeItem> items = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (NodeInfo node : named) {
			List<NodeInfo> chain = new ArrayList<>();
			if (node.type() != NodeType.FILE) {
				chain.add(node); // a file has no chunk of its own: its declarations carry the code
			}
			chain.addAll(membersOfNode.get(node.id()));
			for (NodeInfo member : chain) {
				for (FoundChunk chunk : found.getOrDefault(member.id(), List.of())) {
					CodeItem item = code(Priority.NAME_MATCH, chunk, member, 0);
					if (seen.add(item.key())) {
						items.add(item);
					}
				}
			}
		}
		return items;
	}

	// ---- facts ---------------------------------------------------------------------------------

	private static List<FactItem> facts(List<NeighborEdge> edges, List<NodeInfo> named, Neighborhood neighborhood,
			Map<String, NodeInfo> known) {
		List<FactItem> facts = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Map<String, Integer> shown = new LinkedHashMap<>();
		for (NeighborEdge edge : edges) {
			NodeInfo seed = known.get(edge.seedId());
			if (seed == null) {
				continue;
			}
			NodeInfo source = edge.sourceId().equals(seed.id()) ? seed : edge.other();
			NodeInfo target = edge.targetId().equals(seed.id()) ? seed : edge.other();
			shown.merge(edge.seedId(), 1, Integer::sum);
			if (seen.add(edge.sourceId() + ">" + edge.type() + ">" + edge.targetId())) {
				facts.add(FactItem.relationship(source.id(), displayName(source), edge.type(), target.id(), displayName(target)));
			}
		}
		// a named node whose relationships were cut says so, so an answer does not present a partial list as complete
		for (NodeInfo node : named) {
			if (neighborhood.totals().getOrDefault(node.id(), 0) > shown.getOrDefault(node.id(), 0)) {
				facts.add(FactItem.partialList(node.id(), displayName(node)));
			}
		}
		return facts;
	}

	/** {@code UserService} for a type, {@code UserService#create(String)} for a method, {@code helper(a, b)} for a function. */
	static String displayName(NodeInfo node) {
		String qualified = node.qualifiedName() == null ? node.name() : node.qualifiedName();
		if (node.type() == NodeType.FUNCTION) {
			int hash = qualified.indexOf('#');
			return hash >= 0 ? qualified.substring(hash + 1) : qualified;
		}
		int hash = qualified.indexOf('#');
		String owner = hash >= 0 ? qualified.substring(0, hash) : qualified;
		String member = hash >= 0 ? qualified.substring(hash) : "";
		return owner.substring(owner.lastIndexOf('.') + 1) + member;
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static CodeItem code(Priority priority, FoundChunk chunk, NodeInfo node, double similarity) {
		NodeType type = node != null ? node.type() : ContextItem.typeOf(chunk.nodeId());
		String qualifiedName = node != null && node.qualifiedName() != null ? node.qualifiedName()
				: ContextItem.qualifiedNameOf(chunk.nodeId());
		return new CodeItem(priority, chunk.nodeId(), type, qualifiedName, chunk.file(), chunk.startLine(), chunk.endLine(),
				chunk.content(), similarity);
	}

	/** One chunk stands for a neighbouring or sticky node: the first (a type's header, a method's body). */
	private static List<FoundChunk> first(List<FoundChunk> chunks) {
		return chunks == null || chunks.isEmpty() ? List.of() : List.of(chunks.getFirst());
	}
}
