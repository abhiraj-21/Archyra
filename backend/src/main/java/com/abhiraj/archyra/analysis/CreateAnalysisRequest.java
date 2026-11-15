package com.abhiraj.archyra.analysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /api/v1/analyses}.
 *
 * <p>Only {@code https://github.com/{owner}/{repo}} URLs are accepted. The pattern rejects other
 * schemes and hosts (including {@code file://}), extra path segments, query strings, and any
 * {@code ..} sequence, so the URL is safe to hand to git later.
 */
public record CreateAnalysisRequest(

		@NotBlank(message = "repositoryUrl must not be blank")
		@Size(max = 200, message = "repositoryUrl must be at most 200 characters")
		@Pattern(
				regexp = "^(?!.*\\.\\.)https://github\\.com/[A-Za-z0-9-]+/(?!\\.{1,2}$)[A-Za-z0-9_.-]+$",
				message = "repositoryUrl must be a GitHub repository URL like https://github.com/{owner}/{repo}")
		String repositoryUrl
) {
}
