package com.abhiraj.archyra.analysis;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.common.AnalysisInProgressException;
import com.abhiraj.archyra.graph.GraphBuilder;
import com.abhiraj.archyra.usage.UsageLedgerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes every analysis of a user whose account is being deleted. This is deliberately not
 * {@link AnalysisService#delete}: it <b>never refunds a credit</b> (the user's consumption is kept in the usage ledger,
 * and a refund would let deleting the account lower it), so it goes through {@link AnalysisCleanup#purge} and
 * {@link AnalysisRegistrar#purge}, which skip the refund logic.
 *
 * <p>PostgreSQL work (rows, metadata, the Ask AI data through {@code AnalysisDeleting}) runs in the caller's
 * transaction. Neo4j is outside it: the ids are recorded in {@code pending_graph_purges} in that same transaction, the
 * graphs are dropped after it commits ({@link #dropGraphs}), and a graph that could not be dropped stays in the table and
 * is retried by a sweep. Failures log analysis ids only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPurgeService {

	private final AnalysisRepository analysisRepository;
	private final PendingGraphPurgeRepository pending;
	private final AnalysisCleanup cleanup;
	private final GraphBuilder graphBuilder;
	private final Clock clock;

	/**
	 * The repository links of the analyses that still hold a credit (charged, not refunded), one per analysis, for the
	 * usage ledger to keep when the account is deleted. Only the canonical URL and the creation time, never any content.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<UsageLedgerService.RepositoryRef> retainableRepositories(UUID userId) {
		return analysisRepository.findByOwnerIdAndQuotaChargedTrueAndQuotaRefundedFalse(userId).stream()
				.map(analysis -> new UsageLedgerService.RepositoryRef(analysis.getRepositoryUrl(), analysis.getCreatedAt())).toList();
	}

	/**
	 * Deletes all analyses of the user without refunding anything.
	 *
	 * @return their ids, to be passed to {@link #dropGraphs} after the transaction commits
	 * @throws AnalysisInProgressException if one is still queued or running (its pipeline would write into a deleted row)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<UUID> purgeAllOf(UUID userId) {
		List<Analysis> owned = analysisRepository.findByOwnerId(userId);
		if (owned.stream().anyMatch(Analysis::isInProgress)) { // a restored NOT_ANALYZED entry is idle and is simply removed
			throw new AnalysisInProgressException("An analysis is still running. Try again when it has finished.");
		}
		for (Analysis analysis : owned) {
			pending.save(new PendingGraphPurge(analysis.getId(), clock.instant()));
			cleanup.purge(analysis);
		}
		return owned.stream().map(Analysis::getId).toList();
	}

	/**
	 * Deletes the given analyses of one user without refunding anything, for billing's retention when Premium lapses (the
	 * rule "a downgrade keeps the full count" stands; only a deletion the user chooses returns a credit). The same internals as
	 * {@link #purgeAllOf}: metadata, the Ask AI data (index, chunks, conversations, through {@code AnalysisDeleting}) and the
	 * row in the caller's transaction, the ids recorded for {@link #dropGraphs} after the commit. An id that is not the user's,
	 * is gone, or is queued or running now is skipped (the next sweep sees it finished). {@code user_usage} is never touched.
	 *
	 * @return the ids actually deleted, to be passed to {@link #dropGraphs} after the commit
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<UUID> purgeSelected(UUID userId, Collection<UUID> analysisIds) {
		List<UUID> purged = new java.util.ArrayList<>();
		for (UUID id : analysisIds) {
			analysisRepository.findById(id).filter(analysis -> userId.equals(analysis.getOwnerId()) && !analysis.isInProgress())
					.ifPresent(analysis -> {
						pending.save(new PendingGraphPurge(analysis.getId(), clock.instant()));
						cleanup.purge(analysis);
						purged.add(analysis.getId());
					});
		}
		return List.copyOf(purged);
	}

	/** After commit; never throws. A graph Neo4j could not drop now is left in the table for {@link #sweep}. */
	public void dropGraphs(Collection<UUID> analysisIds) {
		for (UUID id : analysisIds) {
			try {
				graphBuilder.delete(id.toString());
				pending.deleteById(id);
			}
			catch (RuntimeException ex) {
				log.warn("Could not drop the graph of deleted analysis {}; it will be retried ({})", id, ex.getClass().getSimpleName());
				try {
					pending.countAttempt(id);
				}
				catch (RuntimeException ignored) {
					// the row is still there, which is what matters
				}
			}
		}
	}

	/** Retries the graphs that could not be dropped when their account was deleted. Idempotent. */
	@Scheduled(fixedDelayString = "${archyra.analysis.graph-purge-sweep-interval:10m}",
			initialDelayString = "${archyra.analysis.graph-purge-sweep-interval:10m}")
	public void sweep() {
		List<UUID> ids = pending.findAll().stream().map(PendingGraphPurge::getAnalysisId).toList();
		if (!ids.isEmpty()) {
			log.info("Retrying the graph purge of {} deleted analyses", ids.size());
			dropGraphs(ids);
		}
	}
}
