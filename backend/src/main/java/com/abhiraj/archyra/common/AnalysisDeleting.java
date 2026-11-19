package com.abhiraj.archyra.common;

import java.util.UUID;

/**
 * Published before the analysis row is deleted. A listener that cannot remove its data throws: the delete stops, the
 * row stays, and a later retry still finds everything.
 */
public record AnalysisDeleting(UUID analysisId) {
}
