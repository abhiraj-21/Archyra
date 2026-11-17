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

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

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
		UsageProperties properties = new UsageProperties(new UsageProperties.FreeTier(4, 9));

		for (UsageType type : UsageType.values()) {
			assertThat(properties.freeLimit(type)).isNotNegative();
		}
	}
}
