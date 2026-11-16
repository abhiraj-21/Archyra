package com.abhiraj.archyra.graph;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/nodes")
@RequiredArgsConstructor
public class NodeController {

	private final GraphService graphService;

	/** {@code nodeId} is the id the graph exposes, e.g. {@code class:com.foo.UserService}; slashes arrive as {@code %2F}. */
	@GetMapping("/{nodeId}")
	public NodeResponse getNode(@PathVariable String analysisId, @PathVariable String nodeId) {
		return graphService.getNode(analysisId, nodeId);
	}
}
