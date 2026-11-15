package com.abhiraj.archyra.technology;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.TechnologyCategory;

class TechnologyCatalogTest {

	private final TechnologyCatalog catalog = new TechnologyCatalog();

	@Test
	void matchesExactNamesCaseInsensitively() {
		assertThat(catalog.match("npm", "React")).hasValueSatisfying(match -> {
			assertThat(match.name()).isEqualTo("React");
			assertThat(match.category()).isEqualTo(TechnologyCategory.FRONTEND_FRAMEWORK);
		});
		assertThat(catalog.match("npm", "reactive-thing")).isEmpty();
	}

	@Test
	void prefixPatternsMatchFamiliesOfPackages() {
		assertThat(catalog.match("npm", "@nestjs/core")).hasValueSatisfying(match -> assertThat(match.name()).isEqualTo("NestJS"));
		assertThat(catalog.match("maven", "org.springframework.boot:spring-boot-starter-web"))
				.hasValueSatisfying(match -> assertThat(match.name()).isEqualTo("Spring Boot"));
		assertThat(catalog.match("go", "github.com/gofiber/fiber/v2")).hasValueSatisfying(match -> assertThat(match.name()).isEqualTo("Fiber"));
	}

	@Test
	void pythonAndRubyNamesIgnoreCaseUnderscoresAndDots() {
		assertThat(catalog.match("pypi", "Scikit_Learn")).hasValueSatisfying(match -> assertThat(match.name()).isEqualTo("scikit-learn"));
		assertThat(catalog.match("pypi", "PyQt5")).isPresent();
		assertThat(catalog.match("rubygems", "rspec-rails")).hasValueSatisfying(match -> assertThat(match.name()).isEqualTo("RSpec"));
	}

	@Test
	void unknownEcosystemsAndNamesMatchNothing() {
		assertThat(catalog.match("cobol", "react")).isEmpty();
		assertThat(catalog.match("npm", "")).isEmpty();
		assertThat(catalog.match("npm", null)).isEmpty();
	}
}
