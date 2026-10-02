package com.rentease.seniorrent


import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private enum class HomeTab(val label: String) {
    DASHBOARD("Dashboard"),
    PROPERTIES("Properties"),
    TENANTS("Tenants")
}

private data class AppUiState(
    val token: String? = null,
    val role: String? = null,
    val properties: List<PropertyDto> = emptyList(),
    val unitsByProperty: Map<Long, List<UnitDto>> = emptyMap(),
    val tenants: List<TenantDto> = emptyList(),
    val invoices: List<InvoiceDto> = emptyList(),
    val tenantDetails: Map<Long, TenantDetailDto> = emptyMap(),
    val tenantLedgers: Map<Long, TenantLedgerDto> = emptyMap(),
    val tenantHistories: Map<Long, List<TenantHistoryDto>> = emptyMap(),
    val isLoading: Boolean = false,
    val tenantLoadingId: Long? = null
)

private data class SelectOption<T>(val value: T, val label: String)

private data class DashboardDebtRow(
    val tenantId: Long,
    val tenantName: String,
    val propertyName: String,
    val unitLabel: String,
    val dueAmount: Double,
    val status: String,
    val invoiceCount: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionStore = SessionStore(this)
        val api = ApiFactory.create(BuildConfig.API_BASE_URL, sessionStore)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0B5C8A),
                    onPrimary = Color.White,
                    secondary = Color(0xFF2E7D32),
                    onSecondary = Color.White,
                    background = Color(0xFFF2F5F7),
                    surface = Color.White,
                    onSurface = Color(0xFF17212B),
                    outline = Color(0xFFB8C5D1)
                ),
                typography = Typography(
                    headlineSmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
                    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    bodyLarge = TextStyle(fontSize = 18.sp),
                    bodyMedium = TextStyle(fontSize = 16.sp),
                    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
                )
            ) {
                RentEaseSeniorApp(api, sessionStore)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RentEaseSeniorApp(api: RentEaseApi, sessionStore: SessionStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var ui by remember {
        mutableStateOf(
            AppUiState(token = sessionStore.accessToken(), role = sessionStore.role())
        )
    }

    var tab by rememberSaveable { mutableStateOf(HomeTab.DASHBOARD) }

    var loginEmail by rememberSaveable { mutableStateOf("owner@rentease.bd") }
    var loginPassword by rememberSaveable { mutableStateOf("Owner@123") }

    var selectedPropertyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var propertyName by rememberSaveable { mutableStateOf("") }
    var propertyAddress1 by rememberSaveable { mutableStateOf("") }
    var propertyAddress2 by rememberSaveable { mutableStateOf("") }
    var propertyDistrict by rememberSaveable { mutableStateOf("Dhaka") }
    var propertyThana by rememberSaveable { mutableStateOf("Banani") }
    var propertyDivision by rememberSaveable { mutableStateOf("DHAKA") }
    var propertyType by rememberSaveable { mutableStateOf("RESIDENTIAL_FLAT") }
    var propertyUnits by rememberSaveable { mutableStateOf("1") }
    var propertyNotes by rememberSaveable { mutableStateOf("") }

    var selectedUnitId by rememberSaveable { mutableStateOf<Long?>(null) }
    var unitIdentifier by rememberSaveable { mutableStateOf("") }
    var unitFloor by rememberSaveable { mutableStateOf("") }
    var unitAreaSqft by rememberSaveable { mutableStateOf("") }
    var unitType by rememberSaveable { mutableStateOf("2BHK") }
    var unitOccupancy by rememberSaveable { mutableStateOf("VACANT") }

    var selectedTenantId by rememberSaveable { mutableStateOf<Long?>(null) }
    var tenantSearch by rememberSaveable { mutableStateOf("") }

    var selectedInvoiceForPayment by rememberSaveable { mutableStateOf<Long?>(null) }
    var paymentAmount by rememberSaveable { mutableStateOf("") }

    val monthKey = remember { YearMonth.now().toString() }

    suspend fun refreshCoreData(token: String, showLoader: Boolean = true) {
        if (showLoader) {
            ui = ui.copy(isLoading = true)
        }

        val bearerToken = bearer(token)
        val properties = safeCall { api.properties(bearerToken) }.getOrElse { throw it }
        val units = properties.associate { property ->
            property.id to safeCall { api.units(property.id, bearerToken) }.getOrElse { emptyList() }
        }
        val tenants = safeCall { api.tenants(bearerToken, status = "ACTIVE") }.getOrElse { throw it }
        val invoices = safeCall { api.invoices(bearerToken) }.getOrElse { throw it }

        ui = ui.copy(
            properties = properties,
            unitsByProperty = units,
            tenants = tenants,
            invoices = invoices,
            isLoading = false
        )

        if (selectedPropertyId != null && properties.none { it.id == selectedPropertyId }) {
            selectedPropertyId = null
            selectedUnitId = null
        }
        if (selectedTenantId != null && tenants.none { it.id == selectedTenantId }) {
            selectedTenantId = null
        }
    }

    suspend fun loadTenantData(tenantId: Long, token: String) {
        ui = ui.copy(tenantLoadingId = tenantId)
        val bearerToken = bearer(token)

        val detail = safeCall { api.tenant(tenantId, bearerToken) }.getOrElse {
            ui = ui.copy(tenantLoadingId = null)
            throw it
        }
        val ledger = safeCall { api.tenantLedger(tenantId, bearerToken) }.getOrElse {
            ui = ui.copy(tenantLoadingId = null)
            throw it
        }
        val history = safeCall { api.tenantHistory(tenantId, bearerToken) }.getOrElse {
            ui = ui.copy(tenantLoadingId = null)
            throw it
        }

        ui = ui.copy(
            tenantDetails = ui.tenantDetails + (tenantId to detail),
            tenantLedgers = ui.tenantLedgers + (tenantId to ledger),
            tenantHistories = ui.tenantHistories + (tenantId to history),
            tenantLoadingId = null
        )
    }

    fun clearPropertyForm() {
        selectedPropertyId = null
        propertyName = ""
        propertyAddress1 = ""
        propertyAddress2 = ""
        propertyDistrict = "Dhaka"
        propertyThana = "Banani"
        propertyDivision = "DHAKA"
        propertyType = "RESIDENTIAL_FLAT"
        propertyUnits = "1"
        propertyNotes = ""
    }

    fun clearUnitForm() {
        selectedUnitId = null
        unitIdentifier = ""
        unitFloor = ""
        unitAreaSqft = ""
        unitType = "2BHK"
        unitOccupancy = "VACANT"
    }

    fun hydratePropertyForm(property: PropertyDto) {
        selectedPropertyId = property.id
        propertyName = property.propertyName
        propertyAddress1 = property.addressLine1.orEmpty()
        propertyAddress2 = property.addressLine2.orEmpty()
        propertyDistrict = property.district ?: "Dhaka"
        propertyThana = property.thana ?: ""
        propertyDivision = property.division ?: "DHAKA"
        propertyType = property.propertyType ?: "RESIDENTIAL_FLAT"
        propertyUnits = (property.totalUnits ?: 1).toString()
        propertyNotes = property.ownerNotes.orEmpty()
        clearUnitForm()
    }

    fun hydrateUnitForm(unit: UnitDto) {
        selectedUnitId = unit.id
        unitIdentifier = unit.unitIdentifier
        unitFloor = unit.floorNumber?.toString().orEmpty()
        unitAreaSqft = unit.areaSqft?.toString().orEmpty()
        unitType = unit.unitType ?: "OTHER"
        unitOccupancy = unit.occupancyStatus
    }

    LaunchedEffect(ui.token) {
        val token = ui.token ?: return@LaunchedEffect
        runCatching { refreshCoreData(token) }
            .onFailure {
                ui = ui.copy(isLoading = false)
                if (sessionStore.accessToken() == null) ui = ui.copy(token = null, role = null)
                snackbarHostState.showSnackbar(it.message ?: "Failed to load data")
            }
    }

    val unitById = remember(ui.unitsByProperty) {
        ui.unitsByProperty.values.flatten().associateBy { it.id }
    }
    val propertyById = remember(ui.properties) { ui.properties.associateBy { it.id } }
    val tenantById = remember(ui.tenants) { ui.tenants.associateBy { it.id } }

    val unpaidInvoices = remember(ui.invoices) {
        ui.invoices
            .filter { it.balanceDueBdt > 0.0 && it.status !in setOf("PAID", "CANCELLED", "VOID") }
            .sortedByDescending { it.balanceDueBdt }
    }

    val dashboardRows = remember(unpaidInvoices, unitById, propertyById, tenantById) {
        unpaidInvoices
            .groupBy { it.tenantId }
            .map { (tenantId, invoices) ->
                val totalDue = invoices.sumOf { it.balanceDueBdt }
                val anyOverdue = invoices.any { isOverdue(it.dueDate) }
                val first = invoices.first()
                val tenantName = first.tenantName
                    ?: tenantById[tenantId]?.fullName
                    ?: "Tenant #$tenantId"
                val propertyName = first.propertyName
                    ?: propertyById[first.propertyId ?: -1L]?.propertyName
                    ?: "Property"
                val unitLabel = first.unitIdentifier
                    ?: unitById[first.propertyUnitId ?: -1L]?.unitIdentifier
                    ?: "Unit"
                val status = if (anyOverdue) "OVERDUE" else first.status
                DashboardDebtRow(
                    tenantId = tenantId,
                    tenantName = tenantName,
                    propertyName = propertyName,
                    unitLabel = unitLabel,
                    dueAmount = totalDue,
                    status = status,
                    invoiceCount = invoices.size
                )
            }
            .sortedByDescending { it.dueAmount }
    }

    val totalDue = remember(unpaidInvoices) { unpaidInvoices.sumOf { it.balanceDueBdt } }
    val overdueCount = remember(unpaidInvoices) { unpaidInvoices.count { isOverdue(it.dueDate) } }

    val tenantDueMap = remember(unpaidInvoices) {
        unpaidInvoices.groupBy { it.tenantId }.mapValues { (_, inv) -> inv.sumOf { it.balanceDueBdt } }
    }

    val filteredTenants = remember(ui.tenants, tenantSearch) {
        if (tenantSearch.isBlank()) {
            ui.tenants
        } else {
            val query = tenantSearch.trim().lowercase(Locale.ROOT)
            ui.tenants.filter {
                it.fullName.lowercase(Locale.ROOT).contains(query) ||
                    it.phonePrimary.contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            if (ui.token != null) {
                TopAppBar(
                    title = { Text("RentEase Senior") },
                    actions = {
                        TextButton(onClick = {
                            val token = ui.token ?: return@TextButton
                            scope.launch {
                                runCatching { refreshCoreData(token) }
                                    .onFailure {
                                        ui = ui.copy(isLoading = false)
                                        snackbarHostState.showSnackbar(it.message ?: "Refresh failed")
                                    }
                            }
                        }) {
                            Text("Refresh")
                        }
                        TextButton(onClick = {
                            sessionStore.clear()
                            ui = AppUiState()
                            tab = HomeTab.DASHBOARD
                            clearPropertyForm()
                            clearUnitForm()
                            selectedTenantId = null
                        }) {
                            Text("Logout")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (ui.token != null) {
                NavigationBar {
                    HomeTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            label = { Text(entry.label) },
                            icon = { Text(entry.label.take(1)) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (ui.token == null) {
            LoginScreen(
                padding = padding,
                email = loginEmail,
                password = loginPassword,
                onEmailChange = { loginEmail = it },
                onPasswordChange = { loginPassword = it },
                onLogin = {
                    scope.launch {
                        safeCall { api.login(LoginRequest(loginEmail.trim(), loginPassword)) }
                            .onSuccess {
                                sessionStore.save(it.accessToken, it.refreshToken, it.role)
                                ui = ui.copy(token = it.accessToken, role = it.role)
                            }
                            .onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Login failed")
                            }
                    }
                }
            )
            return@Scaffold
        }

        val token = ui.token ?: ""
        val bearerToken = bearer(token)

        when (tab) {
            HomeTab.DASHBOARD -> {
                DashboardTab(
                    padding = padding,
                    monthKey = monthKey,
                    totalDue = totalDue,
                    unpaidTenantCount = dashboardRows.size,
                    overdueCount = overdueCount,
                    dashboardRows = dashboardRows,
                    onOpenTenant = { tenantId ->
                        selectedTenantId = tenantId
                        tab = HomeTab.TENANTS
                        scope.launch {
                            runCatching { loadTenantData(tenantId, token) }
                                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Failed to load tenant") }
                        }
                    }
                )
            }

            HomeTab.PROPERTIES -> {
                PropertiesTab(
                    padding = padding,
                    role = ui.role,
                    properties = ui.properties,
                    unitsByProperty = ui.unitsByProperty,
                    selectedPropertyId = selectedPropertyId,
                    selectedUnitId = selectedUnitId,
                    propertyName = propertyName,
                    propertyAddress1 = propertyAddress1,
                    propertyAddress2 = propertyAddress2,
                    propertyDistrict = propertyDistrict,
                    propertyThana = propertyThana,
                    propertyDivision = propertyDivision,
                    propertyType = propertyType,
                    propertyUnits = propertyUnits,
                    propertyNotes = propertyNotes,
                    unitIdentifier = unitIdentifier,
                    unitFloor = unitFloor,
                    unitAreaSqft = unitAreaSqft,
                    unitType = unitType,
                    unitOccupancy = unitOccupancy,
                    onSelectProperty = { hydratePropertyForm(it) },
                    onSelectUnit = { hydrateUnitForm(it) },
                    onPropertyNameChange = { propertyName = it },
                    onPropertyAddress1Change = { propertyAddress1 = it },
                    onPropertyAddress2Change = { propertyAddress2 = it },
                    onPropertyDistrictChange = { propertyDistrict = it },
                    onPropertyThanaChange = { propertyThana = it },
                    onPropertyDivisionChange = { propertyDivision = it },
                    onPropertyTypeChange = { propertyType = it },
                    onPropertyUnitsChange = { propertyUnits = it.filter(Char::isDigit) },
                    onPropertyNotesChange = { propertyNotes = it },
                    onUnitIdentifierChange = { unitIdentifier = it },
                    onUnitFloorChange = { unitFloor = it.filter { c -> c.isDigit() || c == '-' } },
                    onUnitAreaChange = { unitAreaSqft = it.filter { c -> c.isDigit() || c == '.' } },
                    onUnitTypeChange = { unitType = it },
                    onUnitOccupancyChange = { unitOccupancy = it },
                    onClearPropertyForm = {
                        clearPropertyForm()
                        clearUnitForm()
                    },
                    onClearUnitForm = { clearUnitForm() },
                    onSaveProperty = {
                        scope.launch {
                            val totalUnits = propertyUnits.toIntOrNull()
                            if (propertyName.trim().length < 3 || propertyAddress1.trim().length < 5 || totalUnits == null || totalUnits < 1) {
                                snackbarHostState.showSnackbar("Please complete property name, address, and total units")
                                return@launch
                            }

                            val request = UpdatePropertyRequest(
                                propertyName = propertyName.trim(),
                                addressLine1 = propertyAddress1.trim(),
                                addressLine2 = propertyAddress2.trim().ifBlank { null },
                                thana = propertyThana.trim(),
                                district = propertyDistrict.trim(),
                                division = propertyDivision,
                                propertyType = propertyType,
                                totalUnits = totalUnits,
                                ownerNotes = propertyNotes.trim().ifBlank { null }
                            )

                            val result = if (selectedPropertyId == null) {
                                safeCall {
                                    api.createProperty(
                                        bearerToken,
                                        CreatePropertyRequest(
                                            propertyName = request.propertyName,
                                            addressLine1 = request.addressLine1,
                                            addressLine2 = request.addressLine2,
                                            thana = request.thana,
                                            district = request.district,
                                            division = request.division,
                                            propertyType = request.propertyType,
                                            totalUnits = request.totalUnits,
                                            ownerNotes = request.ownerNotes
                                        )
                                    )
                                }
                            } else {
                                safeCall { api.updateProperty(selectedPropertyId ?: -1L, bearerToken, request) }
                            }

                            result.onSuccess {
                                snackbarHostState.showSnackbar(if (selectedPropertyId == null) "Property created" else "Property updated")
                                refreshCoreData(token)
                                if (selectedPropertyId == null) {
                                    clearPropertyForm()
                                }
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Property save failed")
                            }
                        }
                    },
                    onDeleteProperty = {
                        scope.launch {
                            val propertyId = selectedPropertyId
                            if (propertyId == null) {
                                snackbarHostState.showSnackbar("Select a property first")
                                return@launch
                            }
                            safeCallEmpty { api.deleteProperty(propertyId, bearerToken) }
                                .onSuccess {
                                    snackbarHostState.showSnackbar("Property deleted")
                                    clearPropertyForm()
                                    clearUnitForm()
                                    refreshCoreData(token)
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar(it.message ?: "Delete failed")
                                }
                        }
                    },
                    onSaveUnit = {
                        scope.launch {
                            val propertyId = selectedPropertyId
                            if (propertyId == null) {
                                snackbarHostState.showSnackbar("Select a property before adding a unit")
                                return@launch
                            }
                            if (unitIdentifier.trim().isBlank()) {
                                snackbarHostState.showSnackbar("Unit identifier is required")
                                return@launch
                            }

                            val request = UpdateUnitRequest(
                                unitIdentifier = unitIdentifier.trim(),
                                floorNumber = unitFloor.toIntOrNull(),
                                areaSqft = unitAreaSqft.toDoubleOrNull(),
                                unitType = unitType,
                                occupancyStatus = unitOccupancy
                            )

                            val result = if (selectedUnitId == null) {
                                safeCall {
                                    api.createUnit(
                                        propertyId,
                                        bearerToken,
                                        CreateUnitRequest(
                                            unitIdentifier = request.unitIdentifier,
                                            floorNumber = request.floorNumber,
                                            areaSqft = request.areaSqft,
                                            unitType = request.unitType,
                                            occupancyStatus = request.occupancyStatus
                                        )
                                    )
                                }
                            } else {
                                safeCall {
                                    api.updateUnit(propertyId, selectedUnitId ?: -1L, bearerToken, request)
                                }
                            }

                            result.onSuccess {
                                snackbarHostState.showSnackbar(if (selectedUnitId == null) "Unit added" else "Unit updated")
                                clearUnitForm()
                                refreshCoreData(token, showLoader = false)
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Unit save failed")
                            }
                        }
                    }
                )
            }

            HomeTab.TENANTS -> {
                TenantsTab(
                    padding = padding,
                    tenants = filteredTenants,
                    tenantDueMap = tenantDueMap,
                    selectedTenantId = selectedTenantId,
                    tenantSearch = tenantSearch,
                    tenantLoadingId = ui.tenantLoadingId,
                    tenantDetail = selectedTenantId?.let { ui.tenantDetails[it] },
                    tenantLedger = selectedTenantId?.let { ui.tenantLedgers[it] },
                    tenantHistory = selectedTenantId?.let { ui.tenantHistories[it] }.orEmpty(),
                    invoicesForSelectedTenant = ui.invoices
                        .filter { it.tenantId == selectedTenantId }
                        .sortedByDescending { it.billingPeriodStart },
                    selectedInvoiceForPayment = selectedInvoiceForPayment,
                    paymentAmount = paymentAmount,
                    unitLabel = unitById[selectedTenantId?.let { ui.tenantDetails[it]?.propertyUnitId } ?: -1L]?.unitIdentifier,
                    onTenantSearchChange = { tenantSearch = it },
                    onSelectTenant = { tenant ->
                        selectedTenantId = tenant.id
                        selectedInvoiceForPayment = null
                        paymentAmount = ""
                        scope.launch {
                            runCatching { loadTenantData(tenant.id, token) }
                                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Failed to load tenant") }
                        }
                    },
                    onCallTenant = {
                        val phone = selectedTenantId?.let { ui.tenantDetails[it]?.phonePrimary }
                            ?: selectedTenantId?.let { tenantById[it]?.phonePrimary }
                            ?: ""
                        if (phone.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("No phone found for tenant") }
                        } else {
                            val intent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:$phone")
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    scope.launch { snackbarHostState.showSnackbar("Dial app not available") }
                                }
                        }
                    },
                    onGenerateCurrentInvoice = {
                        scope.launch {
                            val tenantId = selectedTenantId
                            if (tenantId == null) {
                                snackbarHostState.showSnackbar("Select a tenant first")
                                return@launch
                            }

                            val already = ui.invoices.any {
                                it.tenantId == tenantId &&
                                    it.billingPeriodStart.startsWith(monthKey) &&
                                    it.status !in setOf("CANCELLED", "VOID")
                            }
                            if (already) {
                                snackbarHostState.showSnackbar("This month invoice already exists")
                                return@launch
                            }

                            safeCall {
                                api.createInvoice(
                                    bearerToken,
                                    CreateInvoiceApiRequest(
                                        tenantId = tenantId,
                                        billingPeriodStart = LocalDate.now().withDayOfMonth(1).toString(),
                                        utilityCharges = emptyList()
                                    )
                                )
                            }.onSuccess {
                                snackbarHostState.showSnackbar("Invoice generated")
                                refreshCoreData(token, showLoader = false)
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Invoice generation failed")
                            }
                        }
                    },
                    onSendSmsForInvoice = { sourceInvoice ->
                        scope.launch {
                            val tenantId = selectedTenantId
                            if (tenantId == null) {
                                snackbarHostState.showSnackbar("Select a tenant first")
                                return@launch
                            }

                            var invoice = sourceInvoice
                            if (invoice.status == "DRAFT") {
                                val sentResult = safeCall { api.sendInvoice(invoice.id, bearerToken) }
                                if (sentResult.isFailure) {
                                    snackbarHostState.showSnackbar(sentResult.exceptionOrNull()?.message ?: "Failed to mark invoice as sent")
                                    return@launch
                                }
                                invoice = sentResult.getOrThrow()
                            }

                            val phone = normalizeBdPhone(
                                ui.tenantDetails[tenantId]?.phonePrimary
                                    ?: tenantById[tenantId]?.phonePrimary
                                    ?: ""
                            )
                            if (phone == null) {
                                snackbarHostState.showSnackbar("Tenant phone is invalid")
                                return@launch
                            }

                            val fallbackLink = invoice.invoiceDownloadUrl
                                ?: "${BuildConfig.INVOICE_BASE_URL}/${invoice.id}"
                            val smsBody = invoice.smsText
                                ?: "Dear ${invoice.tenantName ?: "Tenant"}, invoice link: $fallbackLink"

                            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("smsto:$phone")
                                putExtra("sms_body", smsBody)
                            }

                            runCatching { context.startActivity(smsIntent) }
                                .onSuccess {
                                    snackbarHostState.showSnackbar("SMS app opened")
                                    refreshCoreData(token, showLoader = false)
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("No SMS app available on this device")
                                }
                        }
                    },
                    onSelectInvoiceForPayment = {
                        selectedInvoiceForPayment = it.id
                        paymentAmount = it.balanceDueBdt.toString()
                    },
                    onPaymentAmountChange = { paymentAmount = it },
                    onRecordPayment = {
                        scope.launch {
                            val invoiceId = selectedInvoiceForPayment
                            val amount = paymentAmount.toDoubleOrNull()
                            if (invoiceId == null || amount == null || amount <= 0.0) {
                                snackbarHostState.showSnackbar("Select invoice and valid amount")
                                return@launch
                            }

                            safeCall {
                                api.recordPayment(
                                    invoiceId,
                                    bearerToken,
                                    RecordPaymentRequest(amountBdt = amount)
                                )
                            }.onSuccess {
                                snackbarHostState.showSnackbar("Payment recorded")
                                refreshCoreData(token, showLoader = false)
                                selectedInvoiceForPayment = null
                                paymentAmount = ""
                                selectedTenantId?.let { tid ->
                                    runCatching { loadTenantData(tid, token) }
                                }
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Payment failed")
                            }
                        }
                    }
                )
            }
        }

        if (ui.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun LoginScreen(
    padding: PaddingValues,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF103D63),
                        Color(0xFF2C7099),
                        Color(0xFF8FC3E0)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 36.dp, end = 24.dp)
                .size(130.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 80.dp, start = 24.dp)
                .size(180.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
        )

        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(18.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "RE",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("RentEase", style = MaterialTheme.typography.titleLarge)
                Text("Simple rent management for daily use", style = MaterialTheme.typography.bodyMedium)

                Field(
                    label = "Email",
                    value = email,
                    onValueChange = onEmailChange,
                    keyboardType = KeyboardType.Email
                )
                Field(
                    label = "Password",
                    value = password,
                    onValueChange = onPasswordChange,
                    visualTransformation = PasswordVisualTransformation()
                )

                BigButton(label = "Sign In", onClick = onLogin)
            }
        }
    }
}

@Composable
private fun DashboardTab(
    padding: PaddingValues,
    monthKey: String,
    totalDue: Double,
    unpaidTenantCount: Int,
    overdueCount: Int,
    dashboardRows: List<DashboardDebtRow>,
    onOpenTenant: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Unpaid Dashboard", style = MaterialTheme.typography.titleLarge)
                    Text("Month view: $monthKey", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    KpiText("Total unpaid", money(totalDue))
                    KpiText("Tenants with dues", unpaidTenantCount.toString())
                    KpiText("Overdue invoices", overdueCount.toString())
                }
            }
        }

        if (dashboardRows.isEmpty()) {
            item {
                Card {
                    Text(
                        "No unpaid tenants right now. Great job.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        items(dashboardRows, key = { it.tenantId }) { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenTenant(row.tenantId) }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.tenantName, style = MaterialTheme.typography.titleMedium)
                        StatusBadge(row.status)
                    }
                    Text("${row.propertyName} • ${row.unitLabel}")
                    Text("Due: ${money(row.dueAmount)}", fontWeight = FontWeight.SemiBold)
                    Text("Unpaid invoices: ${row.invoiceCount}")
                    Text("Tap to open tenant details", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun KpiText(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PropertiesTab(
    padding: PaddingValues,
    role: String?,
    properties: List<PropertyDto>,
    unitsByProperty: Map<Long, List<UnitDto>>,
    selectedPropertyId: Long?,
    selectedUnitId: Long?,
    propertyName: String,
    propertyAddress1: String,
    propertyAddress2: String,
    propertyDistrict: String,
    propertyThana: String,
    propertyDivision: String,
    propertyType: String,
    propertyUnits: String,
    propertyNotes: String,
    unitIdentifier: String,
    unitFloor: String,
    unitAreaSqft: String,
    unitType: String,
    unitOccupancy: String,
    onSelectProperty: (PropertyDto) -> Unit,
    onSelectUnit: (UnitDto) -> Unit,
    onPropertyNameChange: (String) -> Unit,
    onPropertyAddress1Change: (String) -> Unit,
    onPropertyAddress2Change: (String) -> Unit,
    onPropertyDistrictChange: (String) -> Unit,
    onPropertyThanaChange: (String) -> Unit,
    onPropertyDivisionChange: (String) -> Unit,
    onPropertyTypeChange: (String) -> Unit,
    onPropertyUnitsChange: (String) -> Unit,
    onPropertyNotesChange: (String) -> Unit,
    onUnitIdentifierChange: (String) -> Unit,
    onUnitFloorChange: (String) -> Unit,
    onUnitAreaChange: (String) -> Unit,
    onUnitTypeChange: (String) -> Unit,
    onUnitOccupancyChange: (String) -> Unit,
    onClearPropertyForm: () -> Unit,
    onClearUnitForm: () -> Unit,
    onSaveProperty: () -> Unit,
    onDeleteProperty: () -> Unit,
    onSaveUnit: () -> Unit
) {
    val selectedUnits = selectedPropertyId?.let { unitsByProperty[it] }.orEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Properties", style = MaterialTheme.typography.titleLarge)
                    Text("Tap a property to edit. Manager role is read-only.")
                }
            }
        }

        items(properties, key = { it.id }) { property ->
            val isSelected = property.id == selectedPropertyId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectProperty(property) }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(property.propertyName, style = MaterialTheme.typography.titleMedium)
                    Text("${property.district ?: "District"} • ${property.thana ?: "Thana"}")
                    Text("Units: ${unitsByProperty[property.id]?.size ?: property.totalUnits ?: 0}")
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (selectedPropertyId == null) "Create Property" else "Edit Property #$selectedPropertyId",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Field("Property Name", propertyName, onPropertyNameChange)
                    Field("Address Line 1", propertyAddress1, onPropertyAddress1Change)
                    Field("Address Line 2", propertyAddress2, onPropertyAddress2Change)
                    Field("District", propertyDistrict, onPropertyDistrictChange)
                    Field("Thana", propertyThana, onPropertyThanaChange)
                    DropdownField(
                        label = "Division",
                        selected = propertyDivision,
                        options = divisionOptions.map { SelectOption(it, it) },
                        onSelect = { onPropertyDivisionChange(it.value) }
                    )
                    DropdownField(
                        label = "Property Type",
                        selected = propertyType,
                        options = propertyTypeOptions.map { SelectOption(it, it) },
                        onSelect = { onPropertyTypeChange(it.value) }
                    )
                    Field(
                        label = "Total Units",
                        value = propertyUnits,
                        onValueChange = onPropertyUnitsChange,
                        keyboardType = KeyboardType.Number
                    )
                    Field("Notes", propertyNotes, onPropertyNotesChange)

                    if (role == "OWNER") {
                        BigButton(
                            label = if (selectedPropertyId == null) "Save Property" else "Update Property",
                            onClick = onSaveProperty
                        )
                        if (selectedPropertyId != null) {
                            Button(
                                onClick = onDeleteProperty,
                                modifier = Modifier.fillMaxWidth().height(54.dp)
                            ) {
                                Text("Delete Selected Property")
                            }
                        }
                        TextButton(onClick = onClearPropertyForm, modifier = Modifier.fillMaxWidth()) {
                            Text("Clear Property Form")
                        }
                    } else {
                        Text("Manager role can view but not edit properties.")
                    }
                }
            }
        }

        if (selectedPropertyId != null) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Units in Property", style = MaterialTheme.typography.titleLarge)

                        if (selectedUnits.isEmpty()) {
                            Text("No units yet")
                        }

                        selectedUnits.forEach { unit ->
                            val selected = unit.id == selectedUnitId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Color(0xFFEAF4FB) else Color.Transparent)
                                    .clickable { onSelectUnit(unit) }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(unit.unitIdentifier, fontWeight = FontWeight.SemiBold)
                                    Text("${unit.occupancyStatus} • ${unit.unitType ?: "OTHER"}")
                                }
                                Text("Tap to edit")
                            }
                        }

                        HorizontalDivider()
                        Text(
                            if (selectedUnitId == null) "Add Unit" else "Edit Unit #$selectedUnitId",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Field("Unit Identifier", unitIdentifier, onUnitIdentifierChange)
                        Field("Floor Number", unitFloor, onUnitFloorChange, keyboardType = KeyboardType.Number)
                        Field("Area (sqft)", unitAreaSqft, onUnitAreaChange, keyboardType = KeyboardType.Decimal)
                        DropdownField(
                            label = "Unit Type",
                            selected = unitType,
                            options = unitTypeOptions.map { SelectOption(it, it) },
                            onSelect = { onUnitTypeChange(it.value) }
                        )
                        DropdownField(
                            label = "Occupancy",
                            selected = unitOccupancy,
                            options = occupancyOptions.map { SelectOption(it, it) },
                            onSelect = { onUnitOccupancyChange(it.value) }
                        )

                        if (role == "OWNER") {
                            BigButton(
                                label = if (selectedUnitId == null) "Save Unit" else "Update Unit",
                                onClick = onSaveUnit
                            )
                            TextButton(onClick = onClearUnitForm, modifier = Modifier.fillMaxWidth()) {
                                Text("Clear Unit Form")
                            }
                        } else {
                            Text("Manager role can view units but cannot edit.")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TenantsTab(
    padding: PaddingValues,
    tenants: List<TenantDto>,
    tenantDueMap: Map<Long, Double>,
    selectedTenantId: Long?,
    tenantSearch: String,
    tenantLoadingId: Long?,
    tenantDetail: TenantDetailDto?,
    tenantLedger: TenantLedgerDto?,
    tenantHistory: List<TenantHistoryDto>,
    invoicesForSelectedTenant: List<InvoiceDto>,
    selectedInvoiceForPayment: Long?,
    paymentAmount: String,
    unitLabel: String?,
    onTenantSearchChange: (String) -> Unit,
    onSelectTenant: (TenantDto) -> Unit,
    onCallTenant: () -> Unit,
    onGenerateCurrentInvoice: () -> Unit,
    onSendSmsForInvoice: (InvoiceDto) -> Unit,
    onSelectInvoiceForPayment: (InvoiceDto) -> Unit,
    onPaymentAmountChange: (String) -> Unit,
    onRecordPayment: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tenants", style = MaterialTheme.typography.titleLarge)
                    Field("Search by name or phone", tenantSearch, onTenantSearchChange)
                }
            }
        }

        items(tenants, key = { it.id }) { tenant ->
            val selected = tenant.id == selectedTenantId
            val due = tenantDueMap[tenant.id] ?: 0.0
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelectTenant(tenant) }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tenant.fullName, style = MaterialTheme.typography.titleMedium)
                    Text(tenant.phonePrimary)
                    Text(if (due > 0) "Due: ${money(due)}" else "No unpaid balance")
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tenant Details", style = MaterialTheme.typography.titleLarge)

                    if (selectedTenantId == null) {
                        Text("Select a tenant from the list")
                    } else if (tenantLoadingId == selectedTenantId && tenantDetail == null) {
                        CircularProgressIndicator()
                    } else {
                        val detail = tenantDetail
                        val ledger = tenantLedger

                        Text(detail?.fullName ?: "Tenant #$selectedTenantId", style = MaterialTheme.typography.titleMedium)
                        Text("Phone: ${detail?.phonePrimary ?: "-"}")
                        Text("Unit: ${unitLabel ?: "-"}")
                        Text("Status: ${detail?.status ?: "-"}")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = onCallTenant, modifier = Modifier.weight(1f).height(52.dp)) {
                                Text("Call Tenant")
                            }
                            Button(onClick = onGenerateCurrentInvoice, modifier = Modifier.weight(1f).height(52.dp)) {
                                Text("Generate Invoice")
                            }
                        }

                        HorizontalDivider()
                        Text("Ledger", style = MaterialTheme.typography.titleMedium)
                        Text("Total invoiced: ${money(ledger?.totalInvoicedBdt ?: 0.0)}")
                        Text("Outstanding: ${money(ledger?.outstandingBdt ?: 0.0)}")
                        Text("Total paid: ${money(ledger?.totalPaidBdt ?: 0.0)}")

                        if (tenantHistory.isNotEmpty()) {
                            Text("Unit History", style = MaterialTheme.typography.titleMedium)
                            tenantHistory.take(4).forEach {
                                Text("Unit #${it.propertyUnitId} | ${it.startDate ?: "-"} to ${it.endDate ?: "Present"}")
                            }
                        }

                        HorizontalDivider()
                        Text("Invoices", style = MaterialTheme.typography.titleMedium)
                        if (invoicesForSelectedTenant.isEmpty()) {
                            Text("No invoices yet")
                        } else {
                            invoicesForSelectedTenant.take(8).forEach { invoice ->
                                InvoiceItem(
                                    invoice = invoice,
                                    isSelectedForPayment = selectedInvoiceForPayment == invoice.id,
                                    onSelectForPayment = { onSelectInvoiceForPayment(invoice) },
                                    onSendSms = { onSendSmsForInvoice(invoice) }
                                )
                            }
                        }

                        if (selectedInvoiceForPayment != null) {
                            HorizontalDivider()
                            Text("Record Payment", style = MaterialTheme.typography.titleMedium)
                            Field(
                                label = "Amount",
                                value = paymentAmount,
                                onValueChange = onPaymentAmountChange,
                                keyboardType = KeyboardType.Decimal
                            )
                            BigButton("Save Payment", onRecordPayment)
                        }

                        if (ledger != null && ledger.payments.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Previous Payments", style = MaterialTheme.typography.titleMedium)
                            ledger.payments
                                .sortedByDescending { it.paymentDate ?: "" }
                                .take(8)
                                .forEach { payment ->
                                    Text("${payment.paymentDate ?: "-"} • ${money(payment.amountBdt ?: 0.0)} • ${payment.paymentMethod ?: "-"}")
                                }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun InvoiceItem(
    invoice: InvoiceDto,
    isSelectedForPayment: Boolean,
    onSelectForPayment: () -> Unit,
    onSendSms: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = if (isSelectedForPayment) 2.dp else 0.dp,
                color = if (isSelectedForPayment) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invoice #${invoice.id}", fontWeight = FontWeight.SemiBold)
                StatusBadge(if (isOverdue(invoice.dueDate)) "OVERDUE" else invoice.status)
            }
            Text("Period: ${invoice.billingPeriodStart}")
            Text("Due: ${money(invoice.balanceDueBdt)}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSendSms, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text("Send SMS")
                }
                Button(onClick = onSelectForPayment, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text("Apply Payment")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val bg = when (status) {
        "OVERDUE" -> Color(0xFFFFE3E3)
        "PAID" -> Color(0xFFE6F5EC)
        "PARTIALLY_PAID" -> Color(0xFFFFF3DE)
        else -> Color(0xFFE6EEF5)
    }
    val fg = when (status) {
        "OVERDUE" -> Color(0xFF9C1C1C)
        "PAID" -> Color(0xFF1F6A3F)
        "PARTIALLY_PAID" -> Color(0xFF8A5B00)
        else -> Color(0xFF1E4A6A)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(status, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation
    )
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    selected: T?,
    options: List<SelectOption<T>>,
    onSelect: (SelectOption<T>) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.firstOrNull { it.value == selected }?.label ?: "Select"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            value = selectedText,
            onValueChange = {},
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun money(value: Double): String {
    return "BDT ${String.format(Locale.US, "%,.2f", value)}"
}

private fun normalizeBdPhone(raw: String): String? {
    val clean = raw.trim().replace(" ", "")
    return when {
        clean.matches(Regex("^\\+8801[3-9]\\d{8}$")) -> clean
        clean.matches(Regex("^8801[3-9]\\d{8}$")) -> "+$clean"
        clean.matches(Regex("^01[3-9]\\d{8}$")) -> "+88$clean"
        else -> null
    }
}

private fun isOverdue(dueDateRaw: String?): Boolean {
    val dueDate = runCatching { LocalDate.parse(dueDateRaw ?: "") }.getOrNull() ?: return false
    return dueDate.isBefore(LocalDate.now())
}

private val divisionOptions = listOf(
    "DHAKA",
    "CHATTOGRAM",
    "RAJSHAHI",
    "KHULNA",
    "BARISHAL",
    "SYLHET",
    "RANGPUR",
    "MYMENSINGH"
)

private val propertyTypeOptions = listOf(
    "RESIDENTIAL_FLAT",
    "RESIDENTIAL_BUILDING",
    "COMMERCIAL",
    "MIXED_USE",
    "LAND"
)

private val occupancyOptions = listOf(
    "VACANT",
    "OCCUPIED",
    "UNDER_MAINTENANCE"
)

private val unitTypeOptions = listOf(
    "1BHK",
    "2BHK",
    "3BHK",
    "STUDIO",
    "SHOP",
    "OFFICE",
    "OTHER"
)
