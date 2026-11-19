package com.abhiraj.archyra;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.abhiraj.archyra.ai.PgVectorSetup;

/**
 * Real PostgreSQL (with pgvector) and Neo4j for tests that load the Spring context, so no local installs are needed
 * (Docker is). {@link ServiceConnection} points the application at the containers.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PgVectorSetup.class) // so slice tests (@DataJpaTest) also get the vector extension before the schema is created
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		// pgvector build of PostgreSQL: Ask AI stores embeddings in a vector column
		return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
	}

	@Bean
	@ServiceConnection
	Neo4jContainer neo4j() {
		// Neo4j sizes its heap and page cache from the host's memory by default. The suite keeps a dozen Spring contexts (and so a dozen of
		// these containers) alive at once, which on an 8 GB Docker VM got the kernel to kill Neo4j containers (and a developer's own).
		return new Neo4jContainer(DockerImageName.parse("neo4j:5.26-community")).withoutAuthentication()
				.withEnv("NEO4J_server_memory_heap_initial__size", "256m").withEnv("NEO4J_server_memory_heap_max__size", "256m")
				.withEnv("NEO4J_server_memory_pagecache_size", "128m");
	}
}
