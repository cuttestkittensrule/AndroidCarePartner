package org.tidepool.carepartner.backend.data

import org.tidepool.carepartner.backend.PillData
import org.tidepool.sdk.model.confirmations.Confirmation

class FakeBackendDataSource(
    private var invitations: Array<Confirmation> = emptyArray(),
    private val foloweeData: Map<String, PillData> = emptyMap()
): BackendDataSource{
    
    override suspend fun setup() {
    }
    
    override suspend fun getInvitations(): Array<Confirmation> {
        return invitations.copyOf()
    }
    
    override suspend fun getFoloweeData(): Map<String, PillData> {
        return foloweeData
    }
    
    override suspend fun saveEmail() {}
    override suspend fun acceptConfirmation(confirmation: Confirmation) {
        invitations = invitations.filter { it != confirmation }.toTypedArray()
    }
    override suspend fun rejectConfirmation(confirmation: Confirmation) {
        invitations = invitations.filter { it != confirmation }.toTypedArray()
    }
}