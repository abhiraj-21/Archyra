package com.abhiraj.archyra.ai.index;

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
 * Hibernate's {@code ddl-auto=update} created {@code code_chunks.kind} with a CHECK that lists the {@link ChunkKind} values
 * of that day and never changes it, so a database from before {@code README} and {@code MANIFEST} refuses those chunks. This
 * replaces the constraint with one built from the enum. Idempotent and best effort: on a fresh database the constraint
 * already lists every value. TODO: replace with a real migration when migrations are introduced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CodeChunkSchemaUpgrade {

	private final JdbcTemplate jdbc;

	@EventListener(ApplicationStartedEvent.class)
	void upgrade() {
		try {
			Integer stale = jdbc.queryForObject("""
					SELECT count(*) FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
					WHERE rel.relname = 'code_chunks' AND con.contype = 'c' AND pg_get_constraintdef(con.oid) ILIKE '%kind%'
					  AND pg_get_constraintdef(con.oid) NOT ILIKE '%' || ? || '%'""", Integer.class, ChunkKind.MANIFEST.name());
			if (stale == null || stale == 0) {
				return;
			}
			for (String constraint : jdbc.queryForList("""
					SELECT con.conname FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
					WHERE rel.relname = 'code_chunks' AND con.contype = 'c' AND pg_get_constraintdef(con.oid) ILIKE '%kind%'""",
					String.class)) {
				jdbc.execute("ALTER TABLE code_chunks DROP CONSTRAINT \"" + constraint.replace("\"", "") + "\"");
			}
			String values = Arrays.stream(ChunkKind.values()).map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
			jdbc.execute("ALTER TABLE code_chunks ADD CONSTRAINT code_chunks_kind_check CHECK (kind IN (" + values + "))");
			log.info("Widened the code_chunks.kind constraint");
		}
		catch (DataAccessException ex) {
			log.warn("Could not upgrade code_chunks.kind ({})", ex.getClass().getSimpleName());
		}
	}
}
