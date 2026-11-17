package com.abhiraj.archyra.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.abhiraj.archyra.TestcontainersConfiguration;

/** The user tables against the real PostgreSQL, so constraints are the database's own. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserPersistenceTest {

	@Autowired
	private UserRepository users;

	@Autowired
	private LinkedIdentityRepository identities;

	@Test
	void aSavedUserGetsAnIdAndTimestamps() {
		User saved = users.saveAndFlush(User.registered("ada@example.com", "Ada", "{bcrypt}x", Instant.now()));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(users.findByEmail("ada@example.com")).isPresent();
	}

	@Test
	void emailIsUnique() {
		users.saveAndFlush(User.registered("dup@example.com", "One", null, Instant.now()));

		assertThatThrownBy(() -> users.saveAndFlush(User.registered("dup@example.com", "Two", null, Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aProviderIdentityCanBelongToOnlyOneUser() {
		User first = users.saveAndFlush(User.fromSocialLogin("one@example.com", "One"));
		User second = users.saveAndFlush(User.fromSocialLogin("two@example.com", "Two"));
		identities.saveAndFlush(new LinkedIdentity(first.getId(), Provider.GOOGLE, "sub-1", "one@example.com"));
		// the same provider id under another provider is a different identity
		identities.saveAndFlush(new LinkedIdentity(second.getId(), Provider.GITHUB, "sub-1", "two@example.com"));

		assertThatThrownBy(() -> identities.saveAndFlush(
				new LinkedIdentity(second.getId(), Provider.GOOGLE, "sub-1", "two@example.com")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anIdentityIsFoundByProviderAndProviderUserId() {
		User user = users.saveAndFlush(User.fromSocialLogin("find@example.com", "Find"));
		identities.saveAndFlush(new LinkedIdentity(user.getId(), Provider.GITHUB, "42", "find@example.com"));

		assertThat(identities.findByProviderAndProviderUserId(Provider.GITHUB, "42"))
				.get().extracting(LinkedIdentity::getUserId).isEqualTo(user.getId());
		assertThat(identities.findByProviderAndProviderUserId(Provider.GOOGLE, "42")).isEmpty();
	}

	@Test
	void adminsAreCounted() {
		long admins = users.countByRoleAndEnabledTrue(Role.ADMIN);

		users.saveAndFlush(User.administrator("root-" + admins + "@example.com", "Root", "{bcrypt}x", Instant.now()));

		assertThat(users.existsByRole(Role.ADMIN)).isTrue();
		assertThat(users.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(admins + 1);
	}
}
