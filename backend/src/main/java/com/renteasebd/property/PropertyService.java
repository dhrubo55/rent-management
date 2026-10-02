package com.renteasebd.property;

import com.renteasebd.audit.dto.AuditTimelineItemResponse;
import com.renteasebd.common.AppException;
import com.renteasebd.common.AuditService;
import com.renteasebd.common.BdDistricts;
import com.renteasebd.domain.audit.AuditLog;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertySettings;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.property.UtilityChargeConfig;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.property.dto.CreatePropertyRequest;
import com.renteasebd.property.dto.PropertyResponse;
import com.renteasebd.property.dto.PropertySettingsResponse;
import com.renteasebd.property.dto.UnitResponse;
import com.renteasebd.property.dto.UpdatePropertyRequest;
import com.renteasebd.property.dto.UpsertPropertySettingsRequest;
import com.renteasebd.property.dto.UpsertUnitRequest;
import com.renteasebd.property.dto.UpsertUtilityChargeConfigRequest;
import com.renteasebd.property.dto.UtilityChargeConfigResponse;
import com.renteasebd.repository.PropertyRepository;
import com.renteasebd.repository.PropertySettingsRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.AuditLogRepository;
import com.renteasebd.repository.UtilityChargeConfigRepository;
import com.renteasebd.security.ResourceAccessService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final PropertyUnitRepository unitRepository;
    private final PropertySettingsRepository propertySettingsRepository;
    private final UtilityChargeConfigRepository utilityChargeConfigRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final ResourceAccessService access;

    public PropertyService(
        PropertyRepository propertyRepository,
        PropertyUnitRepository unitRepository,
        PropertySettingsRepository propertySettingsRepository,
        UtilityChargeConfigRepository utilityChargeConfigRepository,
        AuditLogRepository auditLogRepository,
        AuditService auditService,
        ResourceAccessService access
    ) {
        this.propertyRepository = propertyRepository;
        this.unitRepository = unitRepository;
        this.propertySettingsRepository = propertySettingsRepository;
        this.utilityChargeConfigRepository = utilityChargeConfigRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> list(Long ownerId, UserRole role) {
        List<Property> properties = access.accessibleProperties(ownerId, role);
        return properties.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PropertyResponse get(Long propertyId, Long actorUserId, UserRole role) {
        return toResponse(getAccessibleProperty(propertyId, actorUserId, role));
    }

    @Transactional
    public PropertyResponse create(CreatePropertyRequest request, Long ownerId) {
        validateDistrict(request.district());

        Property property = new Property();
        property.setPropertyName(request.propertyName());
        property.setAddressLine1(request.addressLine1());
        property.setAddressLine2(request.addressLine2());
        property.setThana(request.thana());
        property.setDistrict(request.district());
        property.setDivision(request.division());
        property.setPropertyType(request.propertyType());
        property.setTotalUnits(request.totalUnits());
        property.setOwnerNotes(request.ownerNotes());
        property.setOwnerId(ownerId);
        Property saved = propertyRepository.save(property);

        auditService.log(ownerId, "PROPERTY_CREATED", "PROPERTY", String.valueOf(saved.getId()), null, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public PropertyResponse update(Long propertyId, UpdatePropertyRequest request, Long actorUserId, UserRole role) {
        validateDistrict(request.district());

        Property property = getAccessibleProperty(propertyId, actorUserId, role);
        PropertyResponse oldState = toResponse(property);
        property.setPropertyName(request.propertyName());
        property.setAddressLine1(request.addressLine1());
        property.setAddressLine2(request.addressLine2());
        property.setThana(request.thana());
        property.setDistrict(request.district());
        property.setDivision(request.division());
        property.setPropertyType(request.propertyType());
        property.setTotalUnits(request.totalUnits());
        property.setOwnerNotes(request.ownerNotes());
        Property saved = propertyRepository.save(property);

        auditService.log(actorUserId, "PROPERTY_UPDATED", "PROPERTY", String.valueOf(saved.getId()), oldState, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(Long propertyId, Long actorUserId, UserRole role) {
        Property property = getAccessibleProperty(propertyId, actorUserId, role);
        PropertyResponse oldState = toResponse(property);
        property.setStatus("INACTIVE");
        propertyRepository.save(property);
        auditService.log(actorUserId, "PROPERTY_DEACTIVATED", "PROPERTY", String.valueOf(property.getId()), oldState, toResponse(property));
    }

    @Transactional(readOnly = true)
    public List<UnitResponse> listUnits(Long propertyId, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);
        return unitRepository.findByPropertyId(propertyId).stream().map(this::toUnitResponse).toList();
    }

    @Transactional
    public UnitResponse addUnit(Long propertyId, UpsertUnitRequest request, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);

        PropertyUnit unit = new PropertyUnit();
        unit.setPropertyId(propertyId);
        unit.setUnitIdentifier(request.unitIdentifier());
        unit.setFloorNumber(request.floorNumber());
        unit.setAreaSqft(request.areaSqft());
        unit.setUnitType(request.unitType());
        unit.setOccupancyStatus(request.occupancyStatus());
        PropertyUnit saved = unitRepository.save(unit);

        assertUnitCountConsistency(propertyId);
        auditService.log(actorUserId, "UNIT_CREATED", "PROPERTY_UNIT", String.valueOf(saved.getId()), null, toUnitResponse(saved));
        logPropertyActivity(actorUserId, propertyId, "PROPERTY_UNIT_CREATED", Map.of("unitId", saved.getId()));
        return toUnitResponse(saved);
    }

    @Transactional
    public UnitResponse updateUnit(Long propertyId, Long unitId, UpsertUnitRequest request, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);
        PropertyUnit unit = unitRepository.findByIdAndPropertyId(unitId, propertyId)
            .orElseThrow(() -> new AppException("UNIT_NOT_FOUND", "Unit not found for property", "unitId"));
        UnitResponse oldState = toUnitResponse(unit);

        unit.setUnitIdentifier(request.unitIdentifier());
        unit.setFloorNumber(request.floorNumber());
        unit.setAreaSqft(request.areaSqft());
        unit.setUnitType(request.unitType());
        unit.setOccupancyStatus(request.occupancyStatus());
        PropertyUnit saved = unitRepository.save(unit);

        auditService.log(actorUserId, "UNIT_UPDATED", "PROPERTY_UNIT", String.valueOf(saved.getId()), oldState, toUnitResponse(saved));
        logPropertyActivity(actorUserId, propertyId, "PROPERTY_UNIT_UPDATED", Map.of("unitId", saved.getId()));
        return toUnitResponse(saved);
    }

    @Transactional(readOnly = true)
    public PropertySettingsResponse getSettings(Long propertyId, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);
        PropertySettings settings = propertySettingsRepository.findByPropertyId(propertyId)
            .orElseGet(() -> defaultSettings(propertyId));
        return toSettingsResponse(settings);
    }

    @Transactional
    public PropertySettingsResponse upsertSettings(
        Long propertyId,
        UpsertPropertySettingsRequest request,
        Long actorUserId,
        UserRole role
    ) {
        getAccessibleProperty(propertyId, actorUserId, role);

        PropertySettings settings = propertySettingsRepository.findByPropertyId(propertyId)
            .orElseGet(() -> defaultSettings(propertyId));
        PropertySettingsResponse oldState = toSettingsResponse(settings);

        settings.setInvoiceDueDayOfMonth(request.invoiceDueDayOfMonth());
        settings.setLateFeeFlatBdt(nonNegativeOrZero(request.lateFeeFlatBdt()));
        settings.setLateFeeGraceDays(request.lateFeeGraceDays());
        settings.setTaxPercent(nonNegativeOrZero(request.taxPercent()));
        settings.setInvoiceFooterText(request.invoiceFooterText());
        PropertySettings saved = propertySettingsRepository.save(settings);

        auditService.log(actorUserId, "PROPERTY_SETTINGS_UPDATED", "PROPERTY_SETTINGS",
            String.valueOf(saved.getId()), oldState, toSettingsResponse(saved));
        logPropertyActivity(actorUserId, propertyId, "PROPERTY_SETTINGS_UPDATED", Map.of("propertySettingsId", saved.getId()));
        return toSettingsResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UtilityChargeConfigResponse> listUtilityChargeConfigs(Long propertyId, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);
        return utilityChargeConfigRepository.findByPropertyIdOrderByDisplayOrderAscIdAsc(propertyId).stream()
            .map(this::toUtilityConfigResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditTimelineItemResponse> activity(Long propertyId, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);
        return auditLogRepository.findTop100ByEntityTypeAndEntityIdOrderByCreatedAtDesc("PROPERTY", String.valueOf(propertyId)).stream()
            .map(this::toTimelineResponse)
            .toList();
    }

    @Transactional
    public UtilityChargeConfigResponse createUtilityChargeConfig(
        Long propertyId,
        UpsertUtilityChargeConfigRequest request,
        Long actorUserId,
        UserRole role
    ) {
        getAccessibleProperty(propertyId, actorUserId, role);
        UtilityChargeConfig config = new UtilityChargeConfig();
        config.setPropertyId(propertyId);
        config.setLabel(request.label().trim());
        config.setEnabled(request.isEnabled());
        config.setDisplayOrder(request.displayOrder());
        UtilityChargeConfig saved = utilityChargeConfigRepository.save(config);

        auditService.log(actorUserId, "UTILITY_CONFIG_CREATED", "UTILITY_CHARGE_CONFIG",
            String.valueOf(saved.getId()), null, toUtilityConfigResponse(saved));
        logPropertyActivity(actorUserId, propertyId, "PROPERTY_UTILITY_CONFIG_CREATED", Map.of("configId", saved.getId()));
        return toUtilityConfigResponse(saved);
    }

    @Transactional
    public UtilityChargeConfigResponse updateUtilityChargeConfig(
        Long propertyId,
        Long configId,
        UpsertUtilityChargeConfigRequest request,
        Long actorUserId,
        UserRole role
    ) {
        getAccessibleProperty(propertyId, actorUserId, role);
        UtilityChargeConfig config = utilityChargeConfigRepository.findByIdAndPropertyId(configId, propertyId)
            .orElseThrow(() -> new AppException("UTILITY_CONFIG_NOT_FOUND", "Utility config not found", "configId"));
        UtilityChargeConfigResponse oldState = toUtilityConfigResponse(config);

        config.setLabel(request.label().trim());
        config.setEnabled(request.isEnabled());
        config.setDisplayOrder(request.displayOrder());
        UtilityChargeConfig saved = utilityChargeConfigRepository.save(config);

        auditService.log(actorUserId, "UTILITY_CONFIG_UPDATED", "UTILITY_CHARGE_CONFIG",
            String.valueOf(saved.getId()), oldState, toUtilityConfigResponse(saved));
        logPropertyActivity(actorUserId, propertyId, "PROPERTY_UTILITY_CONFIG_UPDATED", Map.of("configId", saved.getId()));
        return toUtilityConfigResponse(saved);
    }

    @Transactional
    public void deleteUtilityChargeConfig(Long propertyId, Long configId, Long actorUserId, UserRole role) {
        getAccessibleProperty(propertyId, actorUserId, role);
        UtilityChargeConfig config = utilityChargeConfigRepository.findByIdAndPropertyId(configId, propertyId)
            .orElseThrow(() -> new AppException("UTILITY_CONFIG_NOT_FOUND", "Utility config not found", "configId"));
        UtilityChargeConfigResponse oldState = toUtilityConfigResponse(config);
        utilityChargeConfigRepository.delete(config);
        auditService.log(actorUserId, "UTILITY_CONFIG_DELETED", "UTILITY_CHARGE_CONFIG",
            String.valueOf(config.getId()), oldState, null);
        logPropertyActivity(actorUserId, propertyId, "PROPERTY_UTILITY_CONFIG_DELETED", Map.of("configId", config.getId()));
    }

    private Property getAccessibleProperty(Long propertyId, Long actorUserId, UserRole role) {
        return access.requireProperty(propertyId, actorUserId, role);
    }

    private Property getActiveProperty(Long propertyId) {
        return propertyRepository.findByIdAndStatus(propertyId, "ACTIVE")
            .orElseThrow(() -> new AppException("PROPERTY_NOT_FOUND", "Property not found", "propertyId"));
    }

    private void validateDistrict(String district) {
        if (!BdDistricts.ALL.contains(district)) {
            throw new AppException("INVALID_DISTRICT", "District must be one of Bangladesh 64 official districts", "district");
        }
    }

    private void assertUnitCountConsistency(Long propertyId) {
        Property property = getActiveProperty(propertyId);
        long actual = unitRepository.countByPropertyId(propertyId);
        if (actual > property.getTotalUnits()) {
            throw new AppException("UNIT_COUNT_MISMATCH", "Units exceed property total_units setting", "totalUnits");
        }
    }

    private BigDecimal nonNegativeOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
    }

    private PropertySettings defaultSettings(Long propertyId) {
        PropertySettings settings = new PropertySettings();
        settings.setPropertyId(propertyId);
        settings.setInvoiceDueDayOfMonth(5);
        settings.setLateFeeFlatBdt(BigDecimal.ZERO);
        settings.setLateFeeGraceDays(0);
        settings.setTaxPercent(BigDecimal.ZERO);
        settings.setInvoiceFooterText(null);
        return settings;
    }

    private PropertyResponse toResponse(Property property) {
        return new PropertyResponse(
            property.getId(),
            property.getPropertyName(),
            property.getAddressLine1(),
            property.getAddressLine2(),
            property.getThana(),
            property.getDistrict(),
            property.getDivision(),
            property.getPropertyType(),
            property.getTotalUnits(),
            property.getOwnerNotes(),
            property.getStatus()
        );
    }

    private UnitResponse toUnitResponse(PropertyUnit unit) {
        return new UnitResponse(
            unit.getId(),
            unit.getPropertyId(),
            unit.getUnitIdentifier(),
            unit.getFloorNumber(),
            unit.getAreaSqft(),
            unit.getUnitType(),
            unit.getOccupancyStatus()
        );
    }

    private PropertySettingsResponse toSettingsResponse(PropertySettings settings) {
        return new PropertySettingsResponse(
            settings.getId(),
            settings.getPropertyId(),
            settings.getInvoiceDueDayOfMonth(),
            settings.getLateFeeFlatBdt(),
            settings.getLateFeeGraceDays(),
            settings.getTaxPercent(),
            settings.getInvoiceFooterText()
        );
    }

    private UtilityChargeConfigResponse toUtilityConfigResponse(UtilityChargeConfig config) {
        return new UtilityChargeConfigResponse(
            config.getId(),
            config.getPropertyId(),
            config.getLabel(),
            config.isEnabled(),
            config.getDisplayOrder()
        );
    }

    private AuditTimelineItemResponse toTimelineResponse(AuditLog log) {
        return new AuditTimelineItemResponse(
            log.getId(),
            log.getActorUserId(),
            log.getAction(),
            log.getEntityType(),
            log.getEntityId(),
            log.getCreatedAt()
        );
    }

    private void logPropertyActivity(Long actorUserId, Long propertyId, String action, Object details) {
        auditService.log(actorUserId, action, "PROPERTY", String.valueOf(propertyId), null, details);
    }
}
