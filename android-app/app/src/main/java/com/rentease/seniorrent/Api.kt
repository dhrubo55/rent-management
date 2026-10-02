package com.rentease.seniorrent

import com.google.gson.JsonParser
import com.google.gson.Gson
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.LocalDate

interface RentEaseApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiEnvelope<AuthResponse>>

    @GET("properties")
    suspend fun properties(@Header("Authorization") bearer: String): Response<ApiEnvelope<List<PropertyDto>>>

    @GET("properties/{id}/units")
    suspend fun units(
        @Path("id") propertyId: Long,
        @Header("Authorization") bearer: String
    ): Response<ApiEnvelope<List<UnitDto>>>

    @POST("properties")
    suspend fun createProperty(
        @Header("Authorization") bearer: String,
        @Body request: CreatePropertyRequest
    ): Response<ApiEnvelope<PropertyDto>>

    @PUT("properties/{id}")
    suspend fun updateProperty(
        @Path("id") propertyId: Long,
        @Header("Authorization") bearer: String,
        @Body request: UpdatePropertyRequest
    ): Response<ApiEnvelope<PropertyDto>>

    @DELETE("properties/{id}")
    suspend fun deleteProperty(
        @Path("id") propertyId: Long,
        @Header("Authorization") bearer: String
    ): Response<ApiEnvelope<Void?>>

    @POST("properties/{id}/units")
    suspend fun createUnit(
        @Path("id") propertyId: Long,
        @Header("Authorization") bearer: String,
        @Body request: CreateUnitRequest
    ): Response<ApiEnvelope<UnitDto>>

    @PUT("properties/{id}/units/{unitId}")
    suspend fun updateUnit(
        @Path("id") propertyId: Long,
        @Path("unitId") unitId: Long,
        @Header("Authorization") bearer: String,
        @Body request: UpdateUnitRequest
    ): Response<ApiEnvelope<UnitDto>>

    @Multipart
    @POST("tenants")
    suspend fun createTenant(
        @Header("Authorization") bearer: String,
        @Part parts: List<MultipartBody.Part>
    ): Response<ApiEnvelope<TenantDto>>

    @GET("tenants")
    suspend fun tenants(
        @Header("Authorization") bearer: String,
        @Query("status") status: String? = null,
        @Query("propertyId") propertyId: Long? = null
    ): Response<ApiEnvelope<List<TenantDto>>>

    @GET("tenants/{id}")
    suspend fun tenant(
        @Path("id") tenantId: Long,
        @Header("Authorization") bearer: String
    ): Response<ApiEnvelope<TenantDetailDto>>

    @GET("tenants/{id}/ledger")
    suspend fun tenantLedger(
        @Path("id") tenantId: Long,
        @Header("Authorization") bearer: String
    ): Response<ApiEnvelope<TenantLedgerDto>>

    @GET("tenants/{id}/history")
    suspend fun tenantHistory(
        @Path("id") tenantId: Long,
        @Header("Authorization") bearer: String
    ): Response<ApiEnvelope<List<TenantHistoryDto>>>

    @Multipart
    @PUT("tenants/{id}/nid-image")
    suspend fun uploadNid(
        @Path("id") tenantId: Long,
        @Header("Authorization") bearer: String,
        @Part nidImage: MultipartBody.Part
    ): Response<ApiEnvelope<Void?>>

    @GET("invoices")
    suspend fun invoices(
        @Header("Authorization") bearer: String,
        @Query("billingMonth") billingMonth: String? = null,
        @Query("tenantId") tenantId: Long? = null,
        @Query("propertyId") propertyId: Long? = null,
        @Query("status") status: String? = null
    ): Response<ApiEnvelope<List<InvoiceDto>>>

    @POST("invoices")
    suspend fun createInvoice(
        @Header("Authorization") bearer: String,
        @Body request: CreateInvoiceApiRequest
    ): Response<ApiEnvelope<GenerateInvoiceDto>>

    @POST("invoices/{id}/send")
    suspend fun sendInvoice(
        @Path("id") invoiceId: Long,
        @Header("Authorization") bearer: String
    ): Response<ApiEnvelope<InvoiceDto>>

    @POST("invoices/{id}/payments")
    suspend fun recordPayment(
        @Path("id") invoiceId: Long,
        @Header("Authorization") bearer: String,
        @Body request: RecordPaymentRequest
    ): Response<ApiEnvelope<RecordPaymentResponse>>

    @GET("dashboard/trend")
    suspend fun trend(
        @Header("Authorization") bearer: String,
        @Query("months") months: Int = 1
    ): Response<ApiEnvelope<List<TrendPointDto>>>
}

object ApiFactory {
    fun create(baseUrl: String, sessionStore: SessionStore): RentEaseApi {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val refreshClient = OkHttpClient.Builder().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token = sessionStore.accessToken()
                val isAuth = request.url.encodedPath.contains("/auth/")
                val authenticated = if (token != null && !isAuth) {
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                } else request
                chain.proceed(authenticated)
            }
            .authenticator { _, response ->
                if (response.request.url.encodedPath.contains("/auth/") || response.priorResponse != null) {
                    null
                } else synchronized(sessionStore) {
                    val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
                    val currentToken = sessionStore.accessToken()
                    if (currentToken != null && currentToken != failedToken) {
                        response.request.newBuilder().header("Authorization", "Bearer $currentToken").build()
                    } else {
                        val refreshToken = sessionStore.refreshToken()
                        if (refreshToken == null) null else {
                            val body = Gson().toJson(mapOf("refreshToken" to refreshToken))
                                .toRequestBody("application/json".toMediaType())
                            val request = Request.Builder().url(baseUrl.trimEnd('/') + "/auth/refresh")
                                .post(body).build()
                            val refreshed = runCatching {
                                refreshClient.newCall(request).execute().use { result ->
                                    if (!result.isSuccessful) return@use null
                                    val data = JsonParser.parseString(result.body?.string()).asJsonObject
                                        .getAsJsonObject("data")
                                    val access = data.get("accessToken").asString
                                    val nextRefresh = data.get("refreshToken").asString
                                    val role = data.get("role")?.asString ?: sessionStore.role()
                                    sessionStore.save(access, nextRefresh, role)
                                    access
                                }
                            }.getOrNull()
                            if (refreshed == null) sessionStore.clear()
                            refreshed?.let { response.request.newBuilder().header("Authorization", "Bearer $it").build() }
                        }
                    }
                }
            }
            .addInterceptor(logging)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(RentEaseApi::class.java)
    }
}

data class ApiEnvelope<T>(val success: Boolean, val data: T?, val error: ApiError?)
data class ApiError(val code: String?, val message: String?, val field: String?)

data class LoginRequest(val email: String, val password: String)
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresInSeconds: Long? = null,
    val preferredLanguage: String? = null,
    val role: String
)

data class PropertyDto(
    val id: Long,
    val propertyName: String,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val thana: String? = null,
    val district: String? = null,
    val division: String? = null,
    val propertyType: String? = null,
    val totalUnits: Int? = null,
    val ownerNotes: String? = null,
    val status: String? = null
)

data class UnitDto(
    val id: Long,
    val propertyId: Long? = null,
    val unitIdentifier: String,
    val floorNumber: Int? = null,
    val areaSqft: Double? = null,
    val unitType: String? = null,
    val occupancyStatus: String
)

data class TenantDto(
    val id: Long,
    val fullName: String,
    val phonePrimary: String,
    val propertyUnitId: Long,
    val monthlyRentBdt: Double,
    val hasNidImage: Boolean,
    val status: String? = null
)

data class TenantDetailDto(
    val id: Long,
    val fullName: String,
    val phonePrimary: String,
    val phoneSecondary: String? = null,
    val nidNumber: String? = null,
    val permanentAddress: String? = null,
    val currentAddress: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val leaseStartDate: String? = null,
    val leaseEndDate: String? = null,
    val monthlyRentBdt: Double? = null,
    val securityDepositBdt: Double? = null,
    val securityDepositStatus: String? = null,
    val status: String? = null,
    val hasNidImage: Boolean = false,
    val notes: String? = null,
    val propertyUnitId: Long? = null
)

data class TenantLedgerDto(
    val tenantId: Long,
    val totalInvoicedBdt: Double? = null,
    val outstandingBdt: Double? = null,
    val totalPaidBdt: Double? = null,
    val totalDepositNetBdt: Double? = null,
    val invoices: List<TenantLedgerInvoiceItem> = emptyList(),
    val payments: List<TenantLedgerPaymentItem> = emptyList(),
    val deposits: List<TenantLedgerDepositItem> = emptyList()
)

data class TenantLedgerInvoiceItem(
    val invoiceId: Long,
    val billingPeriodStart: String? = null,
    val billingPeriodEnd: String? = null,
    val dueDate: String? = null,
    val status: String? = null,
    val totalDueBdt: Double? = null,
    val balanceDueBdt: Double? = null
)

data class TenantLedgerPaymentItem(
    val paymentId: Long,
    val invoiceId: Long? = null,
    val paymentDate: String? = null,
    val amountBdt: Double? = null,
    val paymentMethod: String? = null,
    val notes: String? = null
)

data class TenantLedgerDepositItem(
    val transactionId: Long,
    val transactionDate: String? = null,
    val txnType: String? = null,
    val amountBdt: Double? = null,
    val reason: String? = null
)

data class TenantHistoryDto(
    val id: Long,
    val tenantId: Long? = null,
    val propertyUnitId: Long? = null,
    val startDate: String? = null,
    val endDate: String? = null
)

data class InvoiceDto(
    val id: Long,
    val tenantId: Long,
    val tenantName: String?,
    val propertyUnitId: Long? = null,
    val unitIdentifier: String? = null,
    val propertyId: Long?,
    val propertyName: String?,
    val billingPeriodStart: String,
    val billingPeriodEnd: String? = null,
    val dueDate: String,
    val status: String,
    val totalDueBdt: Double,
    val balanceDueBdt: Double,
    val smsText: String?,
    val invoiceDownloadUrl: String?
)

data class UtilityCharge(val label: String, val amountBdt: Double)

class CreateInvoiceApiRequest(
    val tenantId: Long,
    val billingPeriodStart: String,
    val utilityCharges: List<UtilityCharge>
)

data class GenerateInvoiceDto(
    val invoice: InvoiceDto,
    val warnings: List<String>? = null
)

class CreatePropertyRequest(
    val propertyName: String,
    val addressLine1: String,
    val addressLine2: String? = null,
    val thana: String,
    val district: String,
    val division: String,
    val propertyType: String,
    val totalUnits: Int,
    val ownerNotes: String? = null
)

class UpdatePropertyRequest(
    val propertyName: String,
    val addressLine1: String,
    val addressLine2: String? = null,
    val thana: String,
    val district: String,
    val division: String,
    val propertyType: String,
    val totalUnits: Int,
    val ownerNotes: String? = null
)

class CreateUnitRequest(
    val unitIdentifier: String,
    val floorNumber: Int? = null,
    val areaSqft: Double? = null,
    val unitType: String? = null,
    val occupancyStatus: String = "VACANT"
)

class UpdateUnitRequest(
    val unitIdentifier: String,
    val floorNumber: Int? = null,
    val areaSqft: Double? = null,
    val unitType: String? = null,
    val occupancyStatus: String = "VACANT"
)

class RecordPaymentRequest(
    val amountBdt: Double,
    val paymentDate: String = LocalDate.now().toString(),
    val paymentMethod: String = "CASH",
    val notes: String? = null
)

data class RecordPaymentResponse(val invoiceId: Long)

data class TrendPointDto(val month: String, val collectedBdt: Double)

suspend fun <T> safeCall(block: suspend () -> Response<ApiEnvelope<T>>): Result<T> {
    return try {
        val response = block()
        if (!response.isSuccessful) {
            Result.failure(IllegalStateException(readErrorMessage(response)))
        } else {
            val body = response.body()
            when {
                body == null -> Result.failure(IllegalStateException("Empty response from server"))
                !body.success -> Result.failure(IllegalStateException(body.error?.message ?: "Request failed"))
                body.data == null -> Result.failure(IllegalStateException("No data returned from server"))
                else -> Result.success(body.data)
            }
        }
    } catch (ex: Exception) {
        Result.failure(ex)
    }
}

suspend fun safeCallEmpty(block: suspend () -> Response<ApiEnvelope<Void?>>): Result<Unit> {
    return try {
        val response = block()
        if (!response.isSuccessful) {
            Result.failure(IllegalStateException(readErrorMessage(response)))
        } else {
            val body = response.body()
            when {
                body == null -> Result.failure(IllegalStateException("Empty response from server"))
                !body.success -> Result.failure(IllegalStateException(body.error?.message ?: "Request failed"))
                else -> Result.success(Unit)
            }
        }
    } catch (ex: Exception) {
        Result.failure(ex)
    }
}

private fun readErrorMessage(response: Response<*>): String {
    val raw = response.errorBody()?.string().orEmpty()
    if (raw.isBlank()) {
        return "Request failed (${response.code()})"
    }
    return try {
        val root = JsonParser.parseString(raw).asJsonObject
        val error = root.getAsJsonObject("error")
        val message = error?.get("message")?.asString
        val field = error?.get("field")?.asString
        when {
            !message.isNullOrBlank() && !field.isNullOrBlank() -> "$message ($field)"
            !message.isNullOrBlank() -> message
            else -> "Request failed (${response.code()})"
        }
    } catch (_: Exception) {
        "Request failed (${response.code()})"
    }
}

fun bearer(token: String): String = "Bearer $token"
