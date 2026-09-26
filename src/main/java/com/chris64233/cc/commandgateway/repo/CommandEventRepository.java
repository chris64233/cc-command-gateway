package com.chris64233.cc.commandgateway.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.CommandEvent;

public interface CommandEventRepository extends JpaRepository<CommandEvent, Long> {

    Optional<CommandEvent> findByEventId(String eventId);

    List<CommandEvent> findByCommandIdInOrderByOccurredAtAscIdAsc(List<Long> commandIds);
}
