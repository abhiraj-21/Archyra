package com.abhiraj.archyra.ai.llm;

import java.util.List;

import org.springframework.stereotype.Service;

import com.abhiraj.archyra.ai.llm.ProviderRegistry.Slot;
import com.abhiraj.archyra.ai.llm.ProviderStatus.State;
import com.abhiraj.archyra.user.AdminUserService;

import lombok.RequiredArgsConstructor;

/** The admin view of the chain: the database re-check of the caller, then one {@link ProviderStatus} per known provider. */
@Service
@RequiredArgsConstructor
class AdminAiProviders {

	private final ProviderRegistry registry;
	private final ProviderCooldowns cooldowns;
	private final AdminUserService admins;

	List<ProviderStatus> list() {
		admins.requireActiveAdmin();
		return registry.all().stream().map(this::status).toList();
	}

	private ProviderStatus status(Slot slot) {
		ProviderSettings settings = slot.settings();
		var until = cooldowns.coolingUntil(settings.id());
		State state;
		if (!slot.active()) {
			state = State.DISABLED;
		}
		else if (until != null) {
			state = State.COOLING_DOWN;
		}
		else if (slot.guard().wouldRefuse() != null) {
			state = State.RATE_LIMITED_LOCALLY;
		}
		else {
			state = State.AVAILABLE;
		}
		return new ProviderStatus(settings.id(), settings.displayName(), settings.configured(), settings.enabled(), state,
				state == State.COOLING_DOWN ? until : null, slot.guard().requestsToday(), slot.guard().requestsThisMonth());
	}
}
