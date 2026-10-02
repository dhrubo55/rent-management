package com.renteasebd.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renteasebd.domain.property.BdDivision;
import com.renteasebd.domain.property.ManagerPropertyAssignment;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyType;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.tenant.EmergencyRelation;
import com.renteasebd.domain.user.User;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.repository.PropertyRepository;
import com.renteasebd.repository.ManagerPropertyAssignmentRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.repository.UserRepository;
import com.renteasebd.tenant.TenantService;
import com.renteasebd.common.AppException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class ResourceIsolationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PropertyRepository properties;
    @Autowired ManagerPropertyAssignmentRepository assignments;
    @Autowired PropertyUnitRepository units;
    @Autowired TenantRepository tenants;
    @Autowired PasswordEncoder passwords;
    @Autowired TenantService tenantService;

    @Test
    @Transactional
    void ownerCannotReadAnotherOwnersTenantOrNid() throws Exception {
        User ownerA = user("isolation-a@example.com", UserRole.OWNER);
        User ownerB = user("isolation-b@example.com", UserRole.OWNER);
        Tenant foreignTenant = tenant(ownerB);
        String token = token(ownerA);

        String list = mvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("Foreign Tenant");
        mvc.perform(get("/api/v1/tenants/{id}", foreignTenant.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/tenants/{id}/nid-image", foreignTenant.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().is4xxClientError());
        String export = mvc.perform(get("/api/v1/export/tenants").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(export).doesNotContain("Foreign Tenant");
        assertThatThrownBy(() -> tenantService.ledger(foreignTenant.getId(), ownerA.getId(), UserRole.OWNER))
            .isInstanceOf(AppException.class);
    }

    @Test
    @Transactional
    void unassignedManagerCannotEnumerateProperties() throws Exception {
        User owner = user("isolation-owner@example.com", UserRole.OWNER);
        User manager = user("isolation-manager@example.com", UserRole.MANAGER);
        tenant(owner);
        String response = mvc.perform(get("/api/v1/properties")
                .header("Authorization", "Bearer " + token(manager)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Foreign Property");
    }

    @Test
    @Transactional
    void assignedManagerCanSeePropertyButNotIdentityDocument() throws Exception {
        User owner = user("isolation-assigned-owner@example.com", UserRole.OWNER);
        User manager = user("isolation-assigned-manager@example.com", UserRole.MANAGER);
        Tenant tenant = tenant(owner);
        Long propertyId = units.findById(tenant.getPropertyUnitId()).orElseThrow().getPropertyId();
        ManagerPropertyAssignment assignment = new ManagerPropertyAssignment();
        assignment.setManagerUserId(manager.getId());
        assignment.setPropertyId(propertyId);
        assignments.save(assignment);
        String token = token(manager);

        String list = mvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("Foreign Tenant");
        String detail = mvc.perform(get("/api/v1/tenants/{id}", tenant.getId())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("12345678901234567");
        mvc.perform(get("/api/v1/tenants/{id}/nid-image", tenant.getId())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().is4xxClientError());
    }

    private User user(String email, UserRole role) {
        User user = new User();
        user.setFullName(email);
        user.setEmail(email);
        user.setPasswordHash(passwords.encode("Password@123"));
        user.setRole(role);
        user.setPreferredLanguage("en");
        user.setActive(true);
        return users.save(user);
    }

    private Tenant tenant(User owner) {
        Property property = new Property();
        property.setPropertyName("Foreign Property");
        property.setAddressLine1("Road 1");
        property.setThana("Banani");
        property.setDistrict("Dhaka");
        property.setDivision(BdDivision.DHAKA);
        property.setPropertyType(PropertyType.RESIDENTIAL_FLAT);
        property.setTotalUnits(1);
        property.setOwnerId(owner.getId());
        property = properties.save(property);
        PropertyUnit unit = new PropertyUnit();
        unit.setPropertyId(property.getId());
        unit.setUnitIdentifier("A1");
        unit = units.save(unit);
        Tenant tenant = new Tenant();
        tenant.setFullName("Foreign Tenant");
        tenant.setPhonePrimary("+8801712345678");
        tenant.setNidNumber("12345678901234567");
        tenant.setPermanentAddress("Dhaka");
        tenant.setCurrentAddress("Dhaka");
        tenant.setEmergencyContactName("Guardian");
        tenant.setEmergencyContactPhone("+8801812345678");
        tenant.setEmergencyContactRelation(EmergencyRelation.FATHER);
        tenant.setLeaseStartDate(LocalDate.of(2026, 1, 1));
        tenant.setMonthlyRentBdt(new BigDecimal("1000.00"));
        tenant.setSecurityDepositBdt(BigDecimal.ZERO);
        tenant.setPropertyUnitId(unit.getId());
        tenant.setCreatedBy(owner.getId());
        tenant.setNidImage(new byte[] {1, 2, 3});
        tenant.setNidImageMimeType("image/png");
        return tenants.save(tenant);
    }

    private String token(User user) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("email", user.getEmail(), "password", "Password@123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(response);
        return json.path("data").path("accessToken").asText();
    }
}
