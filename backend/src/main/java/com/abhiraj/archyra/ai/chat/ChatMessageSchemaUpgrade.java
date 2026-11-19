package com.abhiraj.archyra.ai.chat;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code chat_messages.provider} used to be an enum column ({@code varchar(10)} with a CHECK on {@code GEMINI}/{@code GROQ}).
 * It is now a free-form provider id ({@code varchar(40)}), and Hibernate's {@code ddl-auto=update} neither widens a column nor
 * drops a constraint, so this does both, and lower-cases the old ids to match the configured ones. Idempotent and best effort:
 * on a fresh database there is nothing to do. TODO: replace with a real migration when migrations are introduced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatMessageSchemaUpgrade {

	private final JdbcTemplate jdbc;

	@EventListener(ApplicationStartedEvent.class)
	void upgrade() {
		try {
			jdbc.execute("ALTER TABLE chat_messages ALTER COLUMN provider TYPE varchar(40)");
			for (String constraint : jdbc.queryForList("""
					SELECT con.conname FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
					WHERE rel.relname = 'chat_messages' AND con.contype = 'c' AND pg_get_constraintdef(con.oid) ILIKE '%provider%'""",
					String.class)) {
				jdbc.execute("ALTER TABLE chat_messages DROP CONSTRAINT \"" + constraint.replace("\"", "") + "\"");
			}
			jdbc.update("UPDATE chat_messages SET provider = lower(provider) WHERE provider IS NOT NULL AND provider <> lower(provider)");
		}
		catch (DataAccessException ex) {
			log.warn("Could not upgrade chat_messages.provider ({})", ex.getClass().getSimpleName());
		}
	}
}
