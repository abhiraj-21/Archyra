package com.abhiraj.archyra.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hibernate creates a {@code CHECK (status IN (...))} on {@code analyses.status} listing the enum values of the day, and
 * {@code ddl-auto=update} never relaxes an existing constraint, so a database created before {@code NOT_ANALYZED} existed
 * would refuse the first restored entry. This drops every status check on {@code analyses} that does not list all the
 * current {@link AnalysisStatus} values and creates one that does (built from the enum, so a later value needs no change
 * here). Idempotent and cheap on every start: a table whose constraint is up to date is not touched. On a fresh database
 * Hibernate already created the right one. The column itself is {@code varchar(20)}, which fits the longest value.
 * TODO: replace with a real migration when migrations are introduced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AnalysisSchemaUpgrade implements ApplicationRunner {

	static final String CONSTRAINT = "analyses_status_check";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	@Override
	public void run(ApplicationArguments args) {
		try {
			transactions.executeWithoutResult(status -> upgrade());
		}
		catch (DataAccessException ex) {
			log.error("Could not widen the status check of analyses ({}); restored entries cannot be stored until it is fixed",
					ex.getClass().getSimpleName());
		}
	}

	/** Package-private so a test can run it again after putting the old constraint back. */
	void upgrade() {
		List<String> current = jdbc.queryForList("""
				select c.conname || '|' || pg_get_constraintdef(c.oid)
				from pg_constraint c
				where c.conrelid = 'analyses'::regclass and c.contype = 'c' and pg_get_constraintdef(c.oid) like '%status%'
				""", String.class);
		boolean upToDate = current.size() == 1 && current.get(0).startsWith(CONSTRAINT + "|")
				&& Arrays.stream(AnalysisStatus.values()).allMatch(value -> current.get(0).contains("'" + value.name() + "'"));
		if (upToDate) {
			return;
		}
		for (String entry : current) {
			jdbc.execute("ALTER TABLE analyses DROP CONSTRAINT \"" + entry.substring(0, entry.indexOf('|')).replace("\"", "") + "\"");
		}
		String values = Arrays.stream(AnalysisStatus.values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
		jdbc.execute("ALTER TABLE analyses ADD CONSTRAINT " + CONSTRAINT + " CHECK (status IN (" + values + "))");
		log.info("Widened the status check of analyses to {} values", AnalysisStatus.values().length);
	}
}
