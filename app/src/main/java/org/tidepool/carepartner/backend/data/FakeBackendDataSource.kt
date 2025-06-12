package org.tidepool.carepartner.backend.data

import org.tidepool.carepartner.backend.PillData
import org.tidepool.sdk.model.confirmations.Confirmation

class FakeBackendDataSource(
    private val invitations: Array<Confirmation> = emptyArray(),
    private val foloweeData: Map<String, PillData> = emptyMap()
): BackendDataSource{
    
    override suspend fun setup() {
    }
    
    override suspend fun getInvitations(): Array<Confirmation> {
        return invitations
    }
    
    override suspend fun getFoloweeData(): Map<String, PillData> {
        return foloweeData
    }
    
}