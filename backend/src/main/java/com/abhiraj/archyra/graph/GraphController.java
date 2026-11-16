package com.abhiraj.archyra.graph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.ir.NodeType;
import com.abhiraj.archyra.ir.RelationshipType;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/graph")
@RequiredArgsConstructor
public class GraphController {

	private final GraphService graphService;

	@GetMapping
	public GraphResponse getGraph(
			@PathVariable String analysisId,
			@RequestParam(required = false) NodeType nodeType,
			@RequestParam(required = false) RelationshipType relationshipType) {
		return graphService.getGraph(analysisId, nodeType, relationshipType);
	}
}
