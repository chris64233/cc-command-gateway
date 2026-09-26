package com.chris64233.cc.commandgateway.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.ReceiptEvent;

public interface ReceiptEventRepository extends JpaRepository<ReceiptEvent, Long> {

    Optional<ReceiptEvent> findByEventId(String eventId);

    List<ReceiptEvent> findByCommandIdInOrderByReceivedAtAsc(List<Long> commandIds);
}
