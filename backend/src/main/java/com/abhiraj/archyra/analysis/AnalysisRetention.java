package com.abhiraj.archyra.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Which repositories a user keeps when Premium lapses, for {@code billing/}. One ranking serves the warning
 * ({@code GET /billing/retention}, the reminder and expiry emails) and the deletion, so they can never disagree.
 *
 * <p>The finished repositories (COMPLETED, and NOT_ANALYZED entries, which hold a credit) are ranked by when the owner last
 * used them ({@link Analysis#lastUsedAt}), newest first, ties by creation, newest first. The first {@code keep} are kept.
 * The rest are at risk, together with every FAILED analysis (it holds no results and never counts toward the kept ones).
 * Running analyses are left alone: the next sweep sees them finished. A user with {@code keep} or fewer finished
 * repositories loses nothing, not even a FAILED one.
 */
@Service
@RequiredArgsConstructor
public class AnalysisRetention {

	/** One repository of the ranking, without anything from its graph. */
	public record Item(UUID analysisId, String repositoryUrl, Instant lastActivityAt, AnalysisStatus status) {
	}

	/** @param atRisk what would be deleted now: the least recently used beyond {@code keep}, then the failed ones */
	public record Retention(List<Item> kept, List<Item> atRisk) {
	}

	private static final Comparator<Analysis> MOST_RECENTLY_USED_FIRST = Comparator.comparing(Analysis::lastUsedAt, Comparator.reverseOrder())
			.thenComparing(Analysis::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(Analysis::getId);

	private final AnalysisRepository analyses;

	@Transactional(readOnly = true)
	public Retention rank(UUID userId, int keep) {
		List<Analysis> owned = analyses.findByOwnerId(userId);
		List<Analysis> finished = owned.stream()
				.filter(analysis -> analysis.getStatus() == AnalysisStatus.COMPLETED || analysis.getStatus() == AnalysisStatus.NOT_ANALYZED)
				.sorted(MOST_RECENTLY_USED_FIRST).toList();
		if (finished.size() <= keep) {
			return new Retention(finished.stream().map(AnalysisRetention::item).toList(), List.of());
		}
		List<Item> atRisk = new ArrayList<>(finished.subList(keep, finished.size()).stream().map(AnalysisRetention::item).toList());
		owned.stream().filter(analysis -> analysis.getStatus() == AnalysisStatus.FAILED).sorted(MOST_RECENTLY_USED_FIRST)
				.map(AnalysisRetention::item).forEach(atRisk::add);
		return new Retention(finished.subList(0, keep).stream().map(AnalysisRetention::item).toList(), List.copyOf(atRisk));
	}

	/** The analyses to delete now: {@link Retention#atRisk} of {@link #rank}. Joins the deletion's transaction. */
	@Transactional
	public List<UUID> selectForRetention(UUID userId, int keep) {
		return rank(userId, keep).atRisk().stream().map(Item::analysisId).toList();
	}

	private static Item item(Analysis analysis) {
		return new Item(analysis.getId(), analysis.getRepositoryUrl(), analysis.lastUsedAt(), analysis.getStatus());
	}
}
