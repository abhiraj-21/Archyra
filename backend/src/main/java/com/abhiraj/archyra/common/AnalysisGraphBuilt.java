package com.abhiraj.archyra.common;

import java.nio.file.Path;
import java.util.UUID;

import com.abhiraj.archyra.ir.CodeGraphIR;

/**
 * Published by the pipeline right after the graph was written and before the analysis is marked COMPLETED, while the
 * clone still exists. Listeners run synchronously on the pipeline thread and read what they need from {@code root}
 * (the clone is deleted when the pipeline ends). A listener must never throw: the pipeline also guards against it, but
 * nothing a listener does may fail an analysis.
 *
 * @param analysisId the analysis
 * @param root       root of the cloned repository
 * @param graph      the resolved code graph that was just written
 */
public record AnalysisGraphBuilt(UUID analysisId, Path root, CodeGraphIR graph) {
}
