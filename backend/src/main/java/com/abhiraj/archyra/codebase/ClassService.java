package com.abhiraj.archyra.codebase;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.graph.ClassResponse;
import com.abhiraj.archyra.graph.GraphService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ClassService {

	private final GraphService graphService;

	/**
	 * One class with its methods, interfaces and dependencies.
	 *
	 * @param classId the class's node id, e.g. {@code class:com.foo.UserService}
	 * @throws com.abhiraj.archyra.common.NodeNotFoundException         if the analysis has no such class
	 * @throws com.abhiraj.archyra.common.AnalysisNotFoundException     if the analysis id is malformed or unknown
	 * @throws com.abhiraj.archyra.common.AnalysisNotCompletedException if the analysis is not {@code COMPLETED}
	 */
	public ClassResponse getClassDetails(String analysisId, String classId) {
		return graphService.getClassDetails(analysisId, classId);
	}
}
