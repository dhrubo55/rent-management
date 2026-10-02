package com.renteasebd.security;

import com.renteasebd.common.AppException;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.repository.ManagerPropertyAssignmentRepository;
import com.renteasebd.repository.PropertyRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ResourceAccessService {
    private final PropertyRepository properties;
    private final PropertyUnitRepository units;
    private final ManagerPropertyAssignmentRepository assignments;

    public ResourceAccessService(PropertyRepository properties, PropertyUnitRepository units,
                                 ManagerPropertyAssignmentRepository assignments) {
        this.properties = properties;
        this.units = units;
        this.assignments = assignments;
    }

    public List<Property> accessibleProperties(Long actorId, UserRole role) {
        if (role == UserRole.OWNER) {
            return properties.findByOwnerIdAndStatus(actorId, "ACTIVE");
        }
        if (role == UserRole.MANAGER) {
            List<Long> ids = assignments.findByManagerUserId(actorId).stream()
                .map(a -> a.getPropertyId()).toList();
            return ids.isEmpty() ? List.of() : properties.findByIdInAndStatus(ids, "ACTIVE");
        }
        return List.of();
    }

    public Property requireProperty(Long propertyId, Long actorId, UserRole role) {
        Property property = properties.findByIdAndStatus(propertyId, "ACTIVE")
            .orElseThrow(() -> denied("propertyId"));
        boolean allowed = role == UserRole.OWNER && property.getOwnerId().equals(actorId)
            || role == UserRole.MANAGER && assignments.existsByManagerUserIdAndPropertyId(actorId, propertyId);
        if (!allowed) throw denied("propertyId");
        return property;
    }

    public Property requireUnit(Long unitId, Long actorId, UserRole role) {
        PropertyUnit unit = units.findById(unitId).orElseThrow(() -> denied("propertyUnitId"));
        return requireProperty(unit.getPropertyId(), actorId, role);
    }

    public void requireTenant(Tenant tenant, Long actorId, UserRole role) {
        requireUnit(tenant.getPropertyUnitId(), actorId, role);
    }

    public void requireIdentityDocument(Tenant tenant, Long actorId, UserRole role) {
        requireTenant(tenant, actorId, role);
        if (role != UserRole.OWNER) throw denied("tenantId");
    }

    private AppException denied(String field) {
        return new AppException("FORBIDDEN", "Resource is unavailable", field);
    }
}
