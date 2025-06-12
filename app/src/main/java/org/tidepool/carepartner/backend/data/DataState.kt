package org.tidepool.carepartner.backend.data

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationService
import net.openid.appauth.TokenResponse
import org.tidepool.carepartner.backend.PersistentData
import org.tidepool.carepartner.backend.PersistentData.Companion.NoAuthorizationException
import org.tidepool.carepartner.backend.PillData
import org.tidepool.carepartner.backend.WarningType
import org.tidepool.carepartner.backend.WarningType.*
import org.tidepool.carepartner.backend.jank.performTokenRequest
import org.tidepool.sdk.CommunicationHelper
import org.tidepool.sdk.model.BloodGlucose.GlucoseReading
import org.tidepool.sdk.model.BloodGlucose.Trend
import org.tidepool.sdk.model.confirmations.Confirmation
import org.tidepool.sdk.model.data.*
import org.tidepool.sdk.model.data.BasalAutomatedData.DeliveryType
import org.tidepool.sdk.model.data.BaseData.DataType.*
import org.tidepool.sdk.model.data.DosingDecisionData.CarbsOnBoard
import org.tidepool.sdk.model.data.DosingDecisionData.InsulinOnBoard
import org.tidepool.sdk.model.metadata.users.TrustUser
import org.tidepool.sdk.model.metadata.users.TrustorUser
import org.tidepool.sdk.model.mgdl
import org.tidepool.sdk.requests.Data.CommaSeparatedArray
import org.tidepool.sdk.requests.receivedInvitations
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class DataState(
    private val authState: AuthState
): ViewModel() {
    companion object {
        const val TAG = "DataState"
        private val minPeriod = 5.minutes
        private val stopTimeout = 10.seconds
    }
    
    private val _data: MutableStateFlow<Map<String, PillData>> = MutableStateFlow(HashMap())
    val data = _data.asStateFlow()
    private val _invitations = MutableStateFlow(emptyArray<Confirmation>())
    val invitations = _invitations.asStateFlow()
    
    private val communicationHelper: CommunicationHelper by lazy {
        CommunicationHelper(
            PersistentData.environment
        )
    }
    
    private fun AuthorizationService.getIdFlow(): Flow<Pair<String, String?>> = flow {
        val userId = communicationHelper.users.getCurrentUserInfo(getAccessToken()).userid
        Log.v(TAG, "Listing users...")
        val trustUsers = communicationHelper.metadata.listUsers(getAccessToken(), userId)
        trustUsers.filterIsInstance<TrustorUser>().filter {
            it.permissions.contains(TrustUser.Permission.view)
        }.forEach {
            emit(it.userid to it.profile?.fullName)
        }
    }
    
    suspend fun update(authService: AuthorizationService) {
        val list = ArrayList<Pair<String, PillData>>()
        authService.getIdFlow()
            .map { (id, name) -> id to authService.getData(id, name) }
            .toCollection(list)
        val map = mutableMapOf(*list.toTypedArray())
        for ((key, value) in _data.value) {
            if (!map.containsKey(key)) {
                map[key] = value
            }
        }
        _data.value = map
        
        authService.updateInvitations()
    }
    
    private suspend fun shutdown() {
        updateContext.close()
        TODO("Cleanly shut down")
    }
    
    private suspend fun AuthorizationService.updateInvitations() {
        val userId = communicationHelper.users.getCurrentUserInfo(getAccessToken()).userid
        val invitations = communicationHelper.confirmations.receivedInvitations(
            getAccessToken(),
            userId
        )
    }
    
    private suspend fun AuthorizationService.exchangeAuthCode() = suspendCoroutine { continuation ->
        val resp = PersistentData.authState.lastAuthorizationResponse ?: throw NoAuthorizationException()
        performTokenRequest(resp.createTokenExchangeRequest()) { newResp, ex ->
            authState.update(newResp, ex)
            if (ex != null) {
                continuation.resumeWithException(ex)
            } else {
                continuation.resume(Unit)
            }
        }
    }
    
    private suspend fun AuthorizationService.getAccessToken(): String {
        if (PersistentData.authState.accessToken == null) {
            exchangeAuthCode()
        }
        return suspendCancellableCoroutine { continuation ->
            authState.performActionWithFreshTokens(this) { accessToken, _, ex ->
                if (ex != null) {
                    continuation.resumeWithException(ex)
                } else {
                    continuation.resume(accessToken!!)
                }
            }
        }
    }
    
    private suspend fun updateAccessToken() {
        val request = authState.createTokenRefreshRequest()
        try {
            val response = performTokenRequest(request)
            authState.update(response, null)
        } catch (ex: AuthorizationException) {
            authState.update(null as TokenResponse?, ex)
        }
    }
    
    private fun getGlucose(result: Array<BaseData>): GlucoseData {
        // >400 -> critical
        // 250..400 -> warning
        // 55..70 -> warning
        // < 55 -> critical
        val dataArr = result.filterIsInstance<ContinuousGlucoseData>().sortedByDescending { value ->
            value.time ?: Instant.MIN
        }
        
        val data = dataArr.getOrNull(0)
        Log.v(TAG, "Data: $data")
        val lastData = dataArr.getOrNull(1)
        
        val warningType = data?.reading?.let { value ->
            when {
                value > 250.mgdl           -> Warning
                value in 55.mgdl..<70.mgdl -> Warning
                value < 55.mgdl            -> Critical
                else                       -> None
            }
        } ?: None
        
        val diff = data?.reading?.let { curr ->
            lastData?.reading?.let { last ->
                curr - last
            }
        }
        
        return GlucoseData(data?.reading, diff, data?.time, data?.trend, warningType)
    }
    
    private data class GlucoseData(
        val mgdl: GlucoseReading?,
        val diff: GlucoseReading?,
        val time: Instant?,
        val trend: Trend?,
        val warningType: WarningType = None
    )
    
    private fun getBasalResult(result: Array<BaseData>): Double? {
        val basalInfo = result.filterIsInstance<BasalAutomatedData>()
            .maxByOrNull { it.time ?: Instant.MIN }
        val lastAutomated = result.filterIsInstance<BasalAutomatedData>()
            .filter { it.deliveryType == DeliveryType.automated }
            .maxByOrNull { it.time ?: Instant.MIN }
        val lastScheduled = result.filterIsInstance<BasalAutomatedData>()
            .filter { it.deliveryType == DeliveryType.scheduled }
            .maxByOrNull { it.time ?: Instant.MIN }
        Log.v(TAG, "Basal Data: $basalInfo")
        Log.v(
            TAG,
            "Last Automated delivery: $lastAutomated (${lastAutomated?.time?.toString() ?: "No timestamp"})"
        )
        Log.v(
            TAG,
            "Last Scheduled delivery: $lastScheduled (${lastScheduled?.time?.toString() ?: "No timestamp"})"
        )
        return basalInfo?.rate
    }
    
    private fun getDosingData(result: Array<BaseData>): Pair<CarbsOnBoard?, InsulinOnBoard?> {
        return result.filterIsInstance<DosingDecisionData>()
            .maxByOrNull { it.time ?: Instant.MIN }?.let {
                Pair(it.carbsOnBoard, it.insulinOnBoard)
            } ?: Pair(null, null)
    }
    
    private fun getLastBolus(result: Array<BaseData>): Instant? {
        return result.filterIsInstance<BolusData>().maxByOrNull { it.time ?: Instant.MIN }?.time
    }
    
    private fun getLastCarbEntry(result: Array<BaseData>): Instant? {
        return result.filterIsInstance<FoodData>().maxByOrNull { it.time ?: Instant.MIN }?.time
    }
    
    private suspend fun AuthorizationService.getData(id: String, name: String?): PillData = coroutineScope {
        var pillData: PillData
        val timeTaken = measureTime {
            var lastBolus: Instant? = null
            var lastCarbEntry: Instant? = null
            var mgdl: GlucoseReading? = null
            var diff: GlucoseReading? = null
            var lastReading: Instant? = null
            var activeCarbs: CarbsOnBoard? = null
            var activeInsulin: InsulinOnBoard? = null
            var basalRate: Double? = null
            lateinit var warningType: WarningType
            var trend: Trend? = null
            Log.v(TAG, "Getting data for user $name ($id)")
            val longJob = launch {
                val startDate = Instant.now().minus(3, ChronoUnit.DAYS)
                val result = communicationHelper.data.getDataForUser(
                    getAccessToken(),
                    userId = id,
                    types = CommaSeparatedArray(bolus, food),
                    startDate = startDate
                )
                
                val lastBolusDeferred = async { getLastBolus(result) }
                val lastCarbEntryDeferred = async { getLastCarbEntry(result) }
                lastBolus = lastBolusDeferred.await()
                lastCarbEntry = lastCarbEntryDeferred.await()
            }
            val shortJob = launch {
                val startDate = Instant.now().minus(630, ChronoUnit.SECONDS) // - 10.5 minutes
                
                val result = communicationHelper.data.getDataForUser(
                    getAccessToken(),
                    userId = id,
                    types = CommaSeparatedArray(dosingDecision, basal, cbg),
                    startDate = startDate
                )
                Log.v(TAG, "getData result Array Length: ${result.size}")
                val glucoseData = async { getGlucose(result) }
                val basalData = async { getBasalResult(result) }
                val dosingData = async { getDosingData(result) }
                val (newMgdl, newDiff, newLastReading, newTrend, newWarningType) = glucoseData.await()
                mgdl = newMgdl
                diff = newDiff
                lastReading = newLastReading
                trend = newTrend
                warningType = newWarningType
                val (newActiveCarbs, newActiveInsulin) = dosingData.await()
                activeCarbs = newActiveCarbs
                activeInsulin = newActiveInsulin
                basalRate = basalData.await()
            }
            
            longJob.join()
            shortJob.join()
            
            pillData = PillData(
                mgdl,
                diff,
                name ?: "User",
                basalRate,
                activeCarbs,
                activeInsulin,
                lastReading,
                lastBolus,
                lastCarbEntry,
                trend,
                warningType,
                arrayOf(lastReading, lastBolus, lastCarbEntry).filterNotNull().maxOrNull()
            )
        }
        
        Log.v(TAG, "User ${pillData.name} took $timeTaken to process")
        
        return@coroutineScope pillData
    }
}