package com.abhiraj.archyra.analysis;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.abhiraj.archyra.usage.UsageLedgerService;
import com.abhiraj.archyra.usage.UsageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gives a returning person back the repositories their credits were spent on, as {@link AnalysisStatus#NOT_ANALYZED}
 * entries (the link only: no graph, index or conversation). Called in the transaction that creates the user, or links an
 * identity, right after {@link UsageLedgerService#restoreInto} raised the counts. Silent: nothing about it reaches a
 * response, an email or a log line (only a count is ever logged, never a URL, user id or key).
 *
 * <p>The entries are created through {@link AnalysisRegistrar}, the only way an analysis row is made. How many: at most the
 * restored {@code REPOSITORY_ANALYSIS} count minus the credits the account already has an analysis for, so entries never
 * exceed the count (fewer links than credits leave the rest spent; more links restore the newest). Which: see
 * {@link UsageLedgerService#repositoriesToRestore} (union over the keys, the largest per-key multiplicity per URL, never a
 * repository the account already holds).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisRestoreService {

	private final UsageLedgerService ledger;
	private final AnalysisRepository analyses;
	private final AnalysisRegistrar registrar;

	/**
	 * @param identityKeys the ledger keys of the new user (create) or of the identity just linked (merge)
	 * @return how many entries were created
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int restoreFor(UUID userId, Collection<String> identityKeys) {
		int used = ledger.usedCount(userId, UsageType.REPOSITORY_ANALYSIS);
		List<Analysis> held = analyses.findByOwnerIdAndQuotaChargedTrueAndQuotaRefundedFalse(userId);
		int room = used - held.size();
		if (room <= 0) {
			return 0;
		}
		List<String> urls = ledger.repositoriesToRestore(identityKeys, held.stream().map(Analysis::getRepositoryUrl).toList(), room);
		urls.forEach(url -> registrar.registerRestored(userId, url));
		if (!urls.isEmpty()) {
			log.info("Restored {} repository entries for a returning account", urls.size());
		}
		return urls.size();
	}
}
