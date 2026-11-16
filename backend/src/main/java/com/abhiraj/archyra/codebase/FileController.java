package com.abhiraj.archyra.codebase;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abhiraj.archyra.graph.FileResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/analyses/{analysisId}/files")
@RequiredArgsConstructor
public class FileController {

	private final FileService fileService;

	@GetMapping
	public FilePageResponse getFiles(
			@PathVariable String analysisId,
			@RequestParam(required = false) String language,
			@RequestParam(required = false) String path,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + FileService.DEFAULT_PAGE_SIZE) int size) {
		return fileService.getFiles(analysisId, language, path, page, size);
	}

	/** {@code fileId} is the file's node id from the listing; its slashes arrive as {@code %2F}. */
	@GetMapping("/{fileId}")
	public FileResponse getFile(@PathVariable String analysisId, @PathVariable String fileId) {
		return fileService.getFile(analysisId, fileId);
	}
}
