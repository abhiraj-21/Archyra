package com.abhiraj.archyra.ir;

import java.util.List;

/** The fully resolved code graph of one repository: the input of the graph builder. */
public record CodeGraphIR(String repositoryName, List<NodeIR> nodes, List<EdgeIR> edges) {

	public CodeGraphIR {
		nodes = List.copyOf(nodes);
		edges = List.copyOf(edges);
	}
}
