package com.abhiraj.archyra.auth;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hibernate's {@code ddl-auto=update} created {@code one_time_tokens.purpose} with a CHECK that lists the enum values of
 * that day and never changes it, so a database from before {@link OneTimePurpose#ACCOUNT_DELETE} refuses the new value.
 * This replaces the constraint with one built from the enum. Idempotent and best effort: on a fresh database the
 * constraint already lists every value. TODO: replace with a real migration when migrations are introduced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OneTimeTokenSchemaUpgrade {

	private final JdbcTemplate jdbc;

	@EventListener(ApplicationStartedEvent.class)
	void upgrade() {
		try {
			Integer stale = jdbc.queryForObject("""
					SELECT count(*) FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
					WHERE rel.relname = 'one_time_tokens' AND con.contype = 'c' AND pg_get_constraintdef(con.oid) ILIKE '%purpose%'
					  AND pg_get_constraintdef(con.oid) NOT ILIKE '%' || ? || '%'""", Integer.class, OneTimePurpose.ACCOUNT_DELETE.name());
			if (stale == null || stale == 0) {
				return;
			}
			for (String constraint : jdbc.queryForList("""
					SELECT con.conname FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
					WHERE rel.relname = 'one_time_tokens' AND con.contype = 'c' AND pg_get_constraintdef(con.oid) ILIKE '%purpose%'""",
					String.class)) {
				jdbc.execute("ALTER TABLE one_time_tokens DROP CONSTRAINT \"" + constraint.replace("\"", "") + "\"");
			}
			String values = Arrays.stream(OneTimePurpose.values()).map(purpose -> "'" + purpose.name() + "'")
					.collect(Collectors.joining(", "));
			jdbc.execute("ALTER TABLE one_time_tokens ADD CONSTRAINT one_time_tokens_purpose_check CHECK (purpose IN (" + values + "))");
			log.info("Widened the one_time_tokens.purpose constraint");
		}
		catch (DataAccessException ex) {
			log.warn("Could not upgrade one_time_tokens.purpose ({})", ex.getClass().getSimpleName());
		}
	}
}
