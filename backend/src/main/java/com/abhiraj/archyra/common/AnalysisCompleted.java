package com.abhiraj.archyra.common;

import java.util.UUID;

/** Published after an analysis was marked COMPLETED. The hand-off for work that must not delay completion. */
public record AnalysisCompleted(UUID analysisId) {
}
