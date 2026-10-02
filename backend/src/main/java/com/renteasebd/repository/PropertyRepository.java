package com.renteasebd.repository;

import com.renteasebd.domain.property.Property;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, Long> {
    List<Property> findByStatus(String status);
    List<Property> findByOwnerIdAndStatus(Long ownerId, String status);
    List<Property> findByIdInAndStatus(List<Long> ids, String status);
    Optional<Property> findByIdAndStatus(Long id, String status);
}
