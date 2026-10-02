package com.renteasebd.domain.property;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "manager_property_assignments")
public class ManagerPropertyAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manager_user_id", nullable = false)
    private Long managerUserId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    public Long getId() { return id; }
    public Long getManagerUserId() { return managerUserId; }
    public void setManagerUserId(Long managerUserId) { this.managerUserId = managerUserId; }
    public Long getPropertyId() { return propertyId; }
    public void setPropertyId(Long propertyId) { this.propertyId = propertyId; }
}
