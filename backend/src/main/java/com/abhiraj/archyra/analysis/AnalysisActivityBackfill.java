package com.abhiraj.archyra.analysis;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code ddl-auto=update} adds {@code analyses.last_activity_at} as an empty column, so every start fills the rows that have
 * none with {@code coalesce(updated_at, created_at)}: until then a repository counts as used when it last changed. Cheap and
 * idempotent (only null rows are written). TODO: a real migration when migrations are introduced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AnalysisActivityBackfill implements ApplicationRunner {

	private final JdbcTemplate jdbc;

	@Override
	public void run(ApplicationArguments args) {
		try {
			int filled = jdbc.update("UPDATE analyses SET last_activity_at = coalesce(updated_at, created_at) WHERE last_activity_at IS NULL");
			if (filled > 0) {
				log.info("Filled the last activity of {} analyses", filled);
			}
		}
		catch (DataAccessException ex) {
			log.warn("Could not fill analyses.last_activity_at ({}); unfilled rows rank by their last change", ex.getClass().getSimpleName());
		}
	}
}
