package com.abhiraj.archyra.analysis;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The graph of an analysis that was deleted together with its owner's account ({@code pending_graph_purges}). The row is
 * written in the account deletion's transaction and removed once Neo4j has dropped the graph, so a graph that could not
 * be deleted right away (Neo4j is outside the PostgreSQL transaction) is retried instead of left behind. Holds only an id.
 */
@Entity
@Table(name = "pending_graph_purges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PendingGraphPurge {

	@Id
	@Column(name = "analysis_id", nullable = false, updatable = false)
	private UUID analysisId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private int attempts;

	PendingGraphPurge(UUID analysisId, Instant createdAt) {
		this.analysisId = analysisId;
		this.createdAt = createdAt;
	}
}
