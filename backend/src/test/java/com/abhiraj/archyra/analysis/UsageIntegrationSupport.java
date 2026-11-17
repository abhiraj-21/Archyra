package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;

/**
 * Usage tests with the pipeline stubbed, so nothing is cloned and an analysis stays {@code QUEUED} until a test
 * says otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"archyra.usage.free-tier.repository-analysis-limit=5", "archyra.usage.free-tier.ai-query-limit=20" })
abstract class UsageIntegrationSupport extends UsageFixtures {

	@MockitoBean
	protected AnalysisPipeline pipeline;

	@BeforeEach
	void theConfiguredAllowanceIsBigEnoughForTheseScenarios() {
		assertThat(limit()).as("archyra.usage.free-tier.repository-analysis-limit").isGreaterThanOrEqualTo(3);
	}
}
