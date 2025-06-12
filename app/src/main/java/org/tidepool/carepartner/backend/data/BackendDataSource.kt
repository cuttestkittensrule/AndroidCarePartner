package org.tidepool.carepartner.backend.data

import org.tidepool.carepartner.backend.PillData
import org.tidepool.sdk.model.confirmations.Confirmation

interface BackendDataSource {
    suspend fun setup()
    suspend fun getInvitations(): Array<Confirmation>
    suspend fun getFoloweeData(): Map<String, PillData>
    suspend fun saveEmail()
    suspend fun acceptConfirmation(confirmation: Confirmation)
    suspend fun rejectConfirmation(confirmation: Confirmation)
}