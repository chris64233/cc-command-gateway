package com.chris64233.cc.commandgateway.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.Lease;

public interface LeaseRepository extends JpaRepository<Lease, Long> {

    Optional<Lease> findByLeaseId(String leaseId);
}
