package com.abhiraj.archyra.graph;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.analysis.AnalysisService;
import com.abhiraj.archyra.common.NodeNotFoundException;
import com.abhiraj.archyra.ir.Language;
import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GraphService {

	private final AnalysisService analysisService;
	private final GraphQueryService graphQueryService;
	private final CodeQueryService codeQueryService;

	/**
	 * The code graph of a completed analysis, optionally narrowed. See
	 * {@link GraphQueryService#getGraph} for what the filters mean.
	 *
	 * @param nodeType         keep only nodes of this type; null for all
	 * @param relationshipType keep only edges of this type; null for all
	 * @throws com.abhiraj.archyra.common.AnalysisNotFoundException     if the id is malformed or unknown
	 * @throws com.abhiraj.archyra.common.AnalysisNotCompletedException if the analysis is not {@code COMPLETED}
	 */
	public GraphResponse getGraph(String analysisId, NodeType nodeType, RelationshipType relationshipType) {
		var id = analysisService.requireCompleted(analysisId);
		return graphQueryService.getGraph(id.toString(), nodeType, relationshipType);
	}

	/**
	 * Details of one node of a completed analysis.
	 *
	 * @throws com.abhiraj.archyra.common.AnalysisNotFoundException     if the analysis id is malformed or unknown
	 * @throws com.abhiraj.archyra.common.AnalysisNotCompletedException if the analysis is not {@code COMPLETED}
	 * @throws NodeNotFoundException                                    if the analysis has no such node
	 */
	public NodeResponse getNode(String analysisId, String nodeId) {
		var id = analysisService.requireCompleted(analysisId);
		return graphQueryService.getNode(id.toString(), nodeId)
				.orElseThrow(() -> new NodeNotFoundException("Node not found"));
	}

	/**
	 * One page of the files of a completed analysis.
	 *
	 * @param language keep only files of this language; null for all
	 * @param path     keep only files at or below this repository path (already normalised); null for all
	 * @param page     zero-based page number
	 * @param size     page size
	 */
	public FilePage getFiles(String analysisId, Language language, String path, int page, int size) {
		var id = analysisService.requireCompleted(analysisId);
		return graphQueryService.getFiles(id.toString(), language, path, page, size);
	}

	/**
	 * A file of a completed analysis with what was extracted from it.
	 *
	 * @param fileId the file's node id, e.g. {@code file:src/main/App.java}
	 * @throws NodeNotFoundException if the analysis has no such file
	 */
	public FileResponse getFileDetails(String analysisId, String fileId) {
		var id = analysisService.requireCompleted(analysisId);
		return codeQueryService.getFileDetails(id.toString(), fileId)
				.orElseThrow(() -> new NodeNotFoundException("File not found"));
	}

	/**
	 * A class of a completed analysis with its methods, interfaces and dependencies.
	 *
	 * @param classId the class's node id, e.g. {@code class:com.foo.UserService}
	 * @throws NodeNotFoundException if the analysis has no such class (interfaces are not classes)
	 */
	public ClassResponse getClassDetails(String analysisId, String classId) {
		var id = analysisService.requireCompleted(analysisId);
		return codeQueryService.getClassDetails(id.toString(), classId)
				.orElseThrow(() -> new NodeNotFoundException("Class not found"));
	}
}
