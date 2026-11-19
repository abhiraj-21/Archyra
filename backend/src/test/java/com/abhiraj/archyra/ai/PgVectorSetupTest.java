package com.abhiraj.archyra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The vector extension check against real databases: one with pgvector and one without. */
class PgVectorSetupTest {

	private static DataSource dataSource(PostgreSQLContainer container) {
		return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
	}

	@Test
	void createsTheExtensionOnAPgvectorDatabaseAndIsIdempotent() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))) {
			postgres.start();
			DataSource dataSource = dataSource(postgres);
			PgVectorSetup setup = new PgVectorSetup();

			setup.pgVectorExtension(dataSource);
			setup.pgVectorExtension(dataSource);

			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
					.isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT '[1,2,3]'::vector <=> '[1,2,3]'::vector", Double.class)).isZero();
		}
	}

	@Test
	void aDatabaseWithoutPgvectorStopsStartupWithAClearMessage() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
			postgres.start();
			DataSource dataSource = dataSource(postgres);

			assertThatThrownBy(() -> new PgVectorSetup().pgVectorExtension(dataSource))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("pgvector")
					.hasMessageContaining("pgvector/pgvector:pg17");
		}
	}
}
