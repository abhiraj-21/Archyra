package com.abhiraj.archyra.ai.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.abhiraj.archyra.ai.AiProperties;
import com.abhiraj.archyra.ai.llm.ChatTurn;
import com.abhiraj.archyra.ai.llm.ProviderId;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Answers that were already given. The key is the analysis, the normalised question, the history that would be sent and
 * the version of the index, so the same question in the same situation is a hit and a re-indexed analysis is not. A hit
 * costs no quota. Only successful, grounded answers are stored, never failures. The key is a hash: no question text is
 * kept as a key. Single instance, in memory.
 */
@Component
class ChatAnswerCache {

	record Entry(String answer, List<ChatSource> sources, ProviderId provider) {
	}

	private final Cache<String, Entry> entries;

	ChatAnswerCache(AiProperties properties) {
		this.entries = Caffeine.newBuilder().expireAfterWrite(properties.cache().ttl()).maximumSize(properties.cache().maxEntries()).build();
	}

	Optional<Entry> get(String key) {
		return Optional.ofNullable(entries.getIfPresent(key));
	}

	void put(String key, Entry entry) {
		entries.put(key, entry);
	}

	static String key(UUID analysisId, String message, List<ChatTurn> history, String indexVersion) {
		MessageDigest digest = sha256();
		update(digest, analysisId.toString());
		update(digest, normalise(message));
		update(digest, indexVersion);
		for (ChatTurn turn : history) {
			update(digest, turn.role().name());
			update(digest, turn.content());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/** Case and runs of whitespace do not make a different question. */
	static String normalise(String message) {
		return message.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static void update(MessageDigest digest, String part) {
		byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
		digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update(bytes);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
