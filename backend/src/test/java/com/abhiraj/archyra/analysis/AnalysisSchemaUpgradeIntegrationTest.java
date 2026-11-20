package com.abhiraj.archyra.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.abhiraj.archyra.TestcontainersConfiguration;
import com.abhiraj.archyra.mail.MailTestConfig;

/**
 * Hibernate's {@code ddl-auto=update} never relaxes the {@code CHECK} it created on {@code analyses.status}, so a database
 * made before {@code NOT_ANALYZED} existed refuses the first restored entry until {@link AnalysisSchemaUpgrade} widens it.
 * The fresh database here is the real one Hibernate created for the current enum; the old one is put back by hand.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MailTestConfig.class })
@ActiveProfiles("test")
class AnalysisSchemaUpgradeIntegrationTest {

	private static final String OLD_VALUES = "'QUEUED','CLONING','PARSING','ANALYZING','BUILDING_GRAPH','COMPLETED','FAILED'";

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AnalysisSchemaUpgrade upgrade;
	@MockitoBean
	AnalysisPipeline pipeline;

	private List<String> statusChecks() {
		return jdbc.queryForList("select c.conname || ' ' || c.oid || ' ' || pg_get_constraintdef(c.oid) from pg_constraint c "
				+ "where c.conrelid = 'analyses'::regclass and c.contype = 'c' and pg_get_constraintdef(c.oid) like '%status%'", String.class);
	}

	private void insertEntry() {
		jdbc.update("insert into analyses (id, user_id, repository_url, status, progress, quota_charged, quota_refunded, created_at, updated_at) "
				+ "values (?, ?, 'https://github.com/o/r', 'NOT_ANALYZED', 0, true, false, now(), now())", UUID.randomUUID(), UUID.randomUUID());
	}

	private void removeEntries() {
		jdbc.update("delete from analyses where status = 'NOT_ANALYZED' and repository_url = 'https://github.com/o/r'");
	}

	@Test
	void aFreshDatabaseAlreadyHasEveryStatusAndTheUpgradeLeavesItAlone() {
		List<String> before = statusChecks();

		assertThat(before).hasSize(1);
		assertThat(before.get(0)).startsWith(AnalysisSchemaUpgrade.CONSTRAINT + " ").contains("'NOT_ANALYZED'");
		assertThat(jdbc.queryForObject("select character_maximum_length from information_schema.columns "
				+ "where table_name = 'analyses' and column_name = 'status'", Integer.class)).isGreaterThanOrEqualTo("NOT_ANALYZED".length());
		insertEntry();
		removeEntries();

		upgrade.upgrade();

		assertThat(statusChecks()).isEqualTo(before); // same constraint, same oid: nothing was dropped
	}

	@Test
	void aDatabaseWithTheOldConstraintRefusesAnEntryUntilTheUpgradeAndTheUpgradeIsIdempotent() {
		try {
			jdbc.execute("ALTER TABLE analyses DROP CONSTRAINT " + AnalysisSchemaUpgrade.CONSTRAINT);
			jdbc.execute("ALTER TABLE analyses ADD CONSTRAINT " + AnalysisSchemaUpgrade.CONSTRAINT + " CHECK (status IN (" + OLD_VALUES + "))");
			assertThatThrownBy(this::insertEntry).isInstanceOf(DataIntegrityViolationException.class);

			upgrade.upgrade();

			assertThat(statusChecks()).hasSize(1);
			assertThat(statusChecks().get(0)).contains("'NOT_ANALYZED'").contains("'COMPLETED'");
			insertEntry();
			removeEntries();
			List<String> afterFirst = statusChecks();
			upgrade.upgrade();
			upgrade.upgrade();
			assertThat(statusChecks()).isEqualTo(afterFirst);
		}
		finally {
			upgrade.upgrade();
			removeEntries();
		}
	}

	@Test
	void anOldConstraintUnderAnotherNameIsReplacedToo() {
		try {
			jdbc.execute("ALTER TABLE analyses DROP CONSTRAINT " + AnalysisSchemaUpgrade.CONSTRAINT);
			jdbc.execute("ALTER TABLE analyses ADD CONSTRAINT some_old_status_check CHECK (status IN (" + OLD_VALUES + "))");

			upgrade.upgrade();

			assertThat(statusChecks()).hasSize(1);
			assertThat(statusChecks().get(0)).startsWith(AnalysisSchemaUpgrade.CONSTRAINT + " ").contains("'NOT_ANALYZED'");
			insertEntry();
		}
		finally {
			upgrade.upgrade();
			removeEntries();
		}
	}
}
