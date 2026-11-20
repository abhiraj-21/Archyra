package com.abhiraj.archyra.billing;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

	/**
	 * Claims an event id in the transaction that does the event's work: 1 for the first delivery, 0 for a repeat. A
	 * concurrent repeat waits for the first transaction and then gets 0, or claims it if that one rolled back.
	 */
	@Modifying
	@Query(value = "insert into webhook_events (event_id, type, received_at, processed_at) values (:id, :type, :now, :now) "
			+ "on conflict (event_id) do nothing", nativeQuery = true)
	int claim(@Param("id") String eventId, @Param("type") String type, @Param("now") Instant now);
}
