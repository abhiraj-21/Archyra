package com.abhiraj.archyra.codebase;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.graph.ClassResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/classes")
@RequiredArgsConstructor
public class ClassController {

	private final ClassService classService;

	/** {@code classId} is the class's node id, e.g. {@code class:com.foo.UserService}. */
	@GetMapping("/{classId}")
	public ClassResponse getClassDetails(@PathVariable String analysisId, @PathVariable String classId) {
		return classService.getClassDetails(analysisId, classId);
	}
}
