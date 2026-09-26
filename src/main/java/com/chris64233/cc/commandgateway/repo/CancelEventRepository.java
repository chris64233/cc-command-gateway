package com.chris64233.cc.commandgateway.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.CancelEvent;

public interface CancelEventRepository extends JpaRepository<CancelEvent, Long> {

    Optional<CancelEvent> findByCancelId(String cancelId);

    List<CancelEvent> findByCommandIdIn(List<Long> commandIds);
}
