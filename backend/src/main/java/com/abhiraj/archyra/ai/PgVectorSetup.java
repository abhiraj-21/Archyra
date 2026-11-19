package com.abhiraj.archyra.ai;

import javax.sql.DataSource;

import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Makes sure the {@code vector} extension exists <em>before</em> Hibernate's {@code ddl-auto=update} creates
 * {@code code_chunks} (whose {@code embedding} column is a {@code vector}). An {@code ApplicationRunner} would be too
 * late, so the EntityManagerFactory is declared to depend on {@link #PG_VECTOR_EXTENSION}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class PgVectorSetup {

	public static final String PG_VECTOR_EXTENSION = "pgVectorExtension";

	@Bean
	static EntityManagerFactoryDependsOnPostProcessor pgVectorDependsOnPostProcessor() {
		return new EntityManagerFactoryDependsOnPostProcessor(PG_VECTOR_EXTENSION);
	}

	@Bean(PG_VECTOR_EXTENSION)
	PgVectorExtension pgVectorExtension(DataSource dataSource) {
		PgVectorExtension extension = new PgVectorExtension(new JdbcTemplate(dataSource));
		extension.ensure();
		return extension;
	}

	/** The result of the check; the bean exists only so that other beans can depend on it. */
	public static final class PgVectorExtension {

		private final JdbcTemplate jdbc;

		PgVectorExtension(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		/** Creates the extension if needed; a database without pgvector, or a role that may not create it, stops startup. */
		void ensure() {
			try {
				jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
			}
			catch (RuntimeException ex) {
				throw new IllegalStateException("Ask AI needs the PostgreSQL 'vector' extension (pgvector), but it is not "
						+ "available in this database. Run a pgvector build of PostgreSQL (for example the "
						+ "pgvector/pgvector:pg17 image) and let the application role create the extension, or run "
						+ "'CREATE EXTENSION vector' once as a superuser in the application database.", ex);
			}
			log.info("pgvector extension is available");
		}
	}
}
