package com.paymentcore.payment.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from OutboxEvent e where e.id = :outboxEventId")
	Optional<OutboxEvent> findByIdForUpdate(@Param("outboxEventId") UUID outboxEventId);

	@Query("""
			select e.id from OutboxEvent e
			where e.status = :status and e.nextAttemptAt <= :now
			order by e.createdAt
			""")
	List<UUID> findDueIds(@Param("status") OutboxStatus status, @Param("now") Instant now, Pageable pageable);

	long countByStatus(OutboxStatus status);

	long countByStatusAndCreatedAtBefore(OutboxStatus status, Instant createdAt);

}
