package com.abhiraj.archyra.common;

import java.util.UUID;

/** Published after an analysis ended as FAILED, so features that stored data for it can drop that data. */
public record AnalysisFailed(UUID analysisId) {
}
