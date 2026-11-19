package com.abhiraj.archyra.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;

/**
 * A database created before provider ids were free-form has {@code chat_messages.provider varchar(10)} with a CHECK on
 * {@code GEMINI}/{@code GROQ}. The startup step must widen it, drop the check and lower-case the old ids, and must be safe to
 * run again.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class ChatMessageSchemaUpgradeTest {

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ChatMessageSchemaUpgrade upgrade;

	private void insertAnswer(UUID id, String provider) {
		jdbc.update("INSERT INTO chat_messages (id, conversation_id, role, content, provider, cached, quota_charged, quota_refunded, created_at)"
				+ " VALUES (?, ?, 'ASSISTANT', 'an answer', ?, false, false, false, now())", id, UUID.randomUUID(), provider);
	}

	@Test
	void anOldEnumColumnBecomesAFreeFormProviderIdAndOldRowsAreLowerCased() {
		UUID old = UUID.randomUUID();
		jdbc.execute("ALTER TABLE chat_messages ALTER COLUMN provider TYPE varchar(10)");
		jdbc.execute("ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_provider_check CHECK (provider in ('GEMINI','GROQ'))");
		insertAnswer(old, "GROQ");

		upgrade.upgrade();
		upgrade.upgrade(); // again: nothing left to do, nothing fails

		assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'chat_messages' AND column_name = 'provider'",
				Integer.class)).isEqualTo(40);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'chat_messages_provider_check'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT provider FROM chat_messages WHERE id = ?", String.class, old)).isEqualTo("groq");
		insertAnswer(UUID.randomUUID(), "openrouter"); // a provider the old CHECK did not know, and longer than 10 characters
		insertAnswer(UUID.randomUUID(), "an-id-of-more-than-ten-characters");
	}
}
