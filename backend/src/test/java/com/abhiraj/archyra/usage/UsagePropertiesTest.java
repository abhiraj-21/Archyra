package com.abhiraj.archyra.usage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UsagePropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(UsageProperties.class)
	static class Config {
	}

	private static final String SECRET = "archyra-test-only-usage-ledger-secret-0123456789";
	private static final String LIMITS = "archyra.usage.free-tier.repository-analysis-limit=3";
	private static final String AI_LIMIT = "archyra.usage.free-tier.ai-query-limit=3";

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
			.withPropertyValues("archyra.usage.ledger-secret=" + SECRET);

	@Test
	void boundLimitsAreReturnedPerType() {
		runner.withPropertyValues("archyra.usage.free-tier.repository-analysis-limit=7", "archyra.usage.free-tier.ai-query-limit=11")
				.run(context -> {
					UsageProperties properties = context.getBean(UsageProperties.class);
					assertThat(properties.freeLimit(UsageType.REPOSITORY_ANALYSIS)).isEqualTo(7);
					assertThat(properties.freeLimit(UsageType.AI_QUERY)).isEqualTo(11);
				});
	}

	@Test
	void zeroIsAValidLimit() {
		runner.withPropertyValues("archyra.usage.free-tier.repository-analysis-limit=0", "archyra.usage.free-tier.ai-query-limit=0")
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void aNegativeLimitFailsStartup() {
		runner.withPropertyValues("archyra.usage.free-tier.repository-analysis-limit=-1", "archyra.usage.free-tier.ai-query-limit=3")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.usage.free-tier.repository-analysis-limit=3", "archyra.usage.free-tier.ai-query-limit=-5")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aMissingLimitFailsStartup() {
		runner.withPropertyValues("archyra.usage.free-tier.repository-analysis-limit=3")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("archyra.usage.free-tier.ai-query-limit=3")
				.run(context -> assertThat(context).hasFailed());
		runner.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aNonNumericLimitFailsStartup() {
		runner.withPropertyValues("archyra.usage.free-tier.repository-analysis-limit=many", "archyra.usage.free-tier.ai-query-limit=3")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void everyUsageTypeHasALimit() {
		UsageProperties properties = new UsageProperties(new UsageProperties.FreeTier(4, 9), SECRET);

		for (UsageType type : UsageType.values()) {
			assertThat(properties.freeLimit(type)).isNotNegative();
		}
	}

	@Test
	void theLedgerSecretIsRequired() {
		new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(LIMITS, AI_LIMIT)
				.run(context -> assertThat(context).hasFailed());
		new ApplicationContextRunner().withUserConfiguration(Config.class)
				.withPropertyValues(LIMITS, AI_LIMIT, "archyra.usage.ledger-secret=   ")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aShortOrObviouslyWeakLedgerSecretFailsStartup() {
		for (String weak : new String[] { "too-short", "0123456789012345678901234567890", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }) {
			new ApplicationContextRunner().withUserConfiguration(Config.class)
					.withPropertyValues(LIMITS, AI_LIMIT, "archyra.usage.ledger-secret=" + weak)
					.run(context -> assertThat(context).hasFailed());
		}
	}

	@Test
	void aLongEnoughLedgerSecretIsAcceptedAndNeverPrinted() {
		new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(LIMITS, AI_LIMIT, "archyra.usage.ledger-secret=" + SECRET)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(UsageProperties.class).toString()).doesNotContain(SECRET);
				});
	}
}
