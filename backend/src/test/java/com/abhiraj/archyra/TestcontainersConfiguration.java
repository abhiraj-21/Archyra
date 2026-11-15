package com.abhiraj.archyra;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Neo4j for tests that load the Spring context, so no local installs are needed
 * (Docker is). {@link ServiceConnection} points the application at the containers.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

	@Bean
	@ServiceConnection
	Neo4jContainer neo4j() {
		return new Neo4jContainer(DockerImageName.parse("neo4j:5.26-community")).withoutAuthentication();
	}
}
