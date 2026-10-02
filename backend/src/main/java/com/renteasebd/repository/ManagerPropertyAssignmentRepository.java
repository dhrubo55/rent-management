package com.renteasebd.repository;

import com.renteasebd.domain.property.ManagerPropertyAssignment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerPropertyAssignmentRepository extends JpaRepository<ManagerPropertyAssignment, Long> {
    List<ManagerPropertyAssignment> findByManagerUserId(Long managerUserId);
    boolean existsByManagerUserIdAndPropertyId(Long managerUserId, Long propertyId);
}
