package com.abhiraj.archyra.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hibernate's {@code ddl-auto=update} adds columns and constraints but never relaxes an existing one. {@code
 * linked_identities.provider_email} was created NOT NULL, and linking (unlike logging in) does not require the
 * provider to report an email, so the constraint is dropped here. Idempotent and best effort: on a fresh database
 * the column is already nullable. TODO: replace with a real migration when migrations are introduced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LinkedIdentitySchemaUpgrade implements ApplicationRunner {

	private final JdbcTemplate jdbc;

	@Override
	public void run(ApplicationArguments args) {
		try {
			jdbc.execute("ALTER TABLE linked_identities ALTER COLUMN provider_email DROP NOT NULL");
		}
		catch (DataAccessException ex) {
			log.warn("Could not relax linked_identities.provider_email ({})", ex.getClass().getSimpleName());
		}
	}
}
