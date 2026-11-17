package com.abhiraj.archyra.analysis;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LegacyAnalysisPurgerTest {

	@Mock
	private AnalysisRepository analysisRepository;
	@Mock
	private AnalysisCleanup cleanup;
	@InjectMocks
	private LegacyAnalysisPurger purger;

	private static Analysis ownerless(String name) {
		Analysis analysis = Analysis.queued("https://github.com/o/" + name, UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(analysis, "ownerId", null); // created before accounts existed
		return analysis;
	}

	@Test
	void deletesEveryAnalysisWithoutAnOwner() {
		Analysis first = ownerless("a");
		Analysis second = ownerless("b");
		given(analysisRepository.findByOwnerIdIsNull()).willReturn(List.of(first, second));

		purger.purgeOwnerlessAnalyses();

		verify(cleanup).delete(first);
		verify(cleanup).delete(second);
	}

	@Test
	void aFailureOnOneAnalysisDoesNotStopTheOthers() {
		Analysis first = ownerless("a");
		Analysis second = ownerless("b");
		given(analysisRepository.findByOwnerIdIsNull()).willReturn(List.of(first, second));
		doThrow(new IllegalStateException("neo4j down")).when(cleanup).delete(first);

		purger.purgeOwnerlessAnalyses();

		verify(cleanup).delete(second);
	}

	@Test
	void doesNothingWhenEveryAnalysisHasAnOwner() {
		given(analysisRepository.findByOwnerIdIsNull()).willReturn(List.of());

		purger.purgeOwnerlessAnalyses();

		verifyNoInteractions(cleanup);
	}

	@Test
	void anAnalysisMustHaveAnOwner() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> Analysis.queued("https://github.com/o/r", null))
				.isInstanceOf(IllegalArgumentException.class);
		verify(cleanup, never()).delete(org.mockito.ArgumentMatchers.any());
	}
}
