package org.tidepool.carepartner.backend.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toCollection
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.TokenResponse
import org.tidepool.carepartner.backend.PersistentData
import org.tidepool.carepartner.backend.PillData
import org.tidepool.carepartner.backend.WarningType
import org.tidepool.carepartner.backend.WarningType.*
import org.tidepool.carepartner.backend.data.DataState.Companion.TAG
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
import kotlin.time.measureTime

class RealBackendDataSource(private val authState: AuthState): BackendDataSource {
    
    private val communicationHelper by lazy { CommunicationHelper(PersistentData.environment) }
    private val gson by CommunicationHelper.Companion::gsonConfig
    
    override suspend fun setup() {
        authState.lastAuthorizationResponse?.let {
            try {
                val resp = performTokenRequest(it.createTokenExchangeRequest())
                authState.update(resp, null)
            } catch (ex: AuthorizationException) {
                authState.update(null as TokenResponse?, ex)
            }
        }
    }
    
    override suspend fun getInvitations(): Array<Confirmation> {
        val userId = communicationHelper.users.getCurrentUserInfo(getAccessToken()).userid
        return communicationHelper.confirmations.receivedInvitations(
            getAccessToken(),
            userId
        )
    }
    
    override suspend fun getFoloweeData(): Map<String, PillData> {
        val list = ArrayList<Pair<String, PillData>>()
        getIdFlow()
            .map { (id, name) -> id to getData(id, name) }
            .toCollection(list)
        return mapOf(*list.toTypedArray())
    }
    
    private fun getIdFlow(): Flow<Pair<String, String?>> = flow {
        val userId = communicationHelper.users.getCurrentUserInfo(getAccessToken()).userid
        Log.v(TAG, "Listing users...")
        val trustUsers = communicationHelper.metadata.listUsers(getAccessToken(), userId)
        trustUsers.filterIsInstance<TrustorUser>().filter {
            it.permissions.contains(TrustUser.Permission.view)
        }.forEach {
            emit(it.userid to it.profile?.fullName)
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
    
    private suspend fun getData(id: String, name: String?): PillData = coroutineScope {
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
    
    private val accessTokenContext = Dispatchers.Default.limitedParallelism(1)
    
    private suspend fun refreshToken() {
        try {
            val response = performTokenRequest(authState.createTokenRefreshRequest())
            authState.update(response, null)
        } catch (ex: AuthorizationException) {
            authState.update(null as TokenResponse?, ex)
        }
    }
    
    private suspend fun getAccessToken(): String {
        if (authState.needsTokenRefresh) {
            withContext(accessTokenContext) {
                if (authState.needsTokenRefresh) {
                    refreshToken()
                }
            }
        }
        return authState.refreshToken ?: throw NullPointerException("No Access token!")
    }
}