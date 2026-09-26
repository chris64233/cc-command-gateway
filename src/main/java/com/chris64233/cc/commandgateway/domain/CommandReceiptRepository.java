package com.chris64233.cc.commandgateway.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommandReceiptRepository extends JpaRepository<CommandReceipt, Long> {

    Optional<CommandReceipt> findByCommandIdAndEventId(Long commandId, String eventId);

    boolean existsByCommandIdAndStatusIn(Long commandId, Collection<CommandStatus> statuses);

    List<CommandReceipt> findByCommandIdInOrderByReceivedAtAsc(Collection<Long> commandIds);
}
