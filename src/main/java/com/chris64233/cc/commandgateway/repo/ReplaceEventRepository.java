package com.chris64233.cc.commandgateway.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.ReplaceEvent;

public interface ReplaceEventRepository extends JpaRepository<ReplaceEvent, Long> {

    Optional<ReplaceEvent> findByReplaceId(String replaceId);

    Optional<ReplaceEvent> findByOldCommandId(Long oldCommandId);

    List<ReplaceEvent> findByOldCommandIdInOrNewCommandIdIn(List<Long> oldCommandIds,
                                                           List<Long> newCommandIds);
}
