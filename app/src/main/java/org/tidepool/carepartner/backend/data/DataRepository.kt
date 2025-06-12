package org.tidepool.carepartner.backend.data

import org.tidepool.carepartner.backend.PillData
import org.tidepool.sdk.model.confirmations.Confirmation

class DataRepository(
    private val backendDataSource: BackendDataSource
) {
    suspend fun getInvitations(): Array<Confirmation> = backendDataSource.getInvitations()
    suspend fun getFoloweeData(): Map<String, PillData> = backendDataSource.getFoloweeData()
    suspend fun setup() = backendDataSource.setup()
    suspend fun saveEmail() = backendDataSource.saveEmail()
    suspend fun acceptConfirmation(confirmation: Confirmation) = backendDataSource.acceptConfirmation(confirmation)
    suspend fun rejectConfirmation(confirmation: Confirmation) = backendDataSource.rejectConfirmation(confirmation)
}