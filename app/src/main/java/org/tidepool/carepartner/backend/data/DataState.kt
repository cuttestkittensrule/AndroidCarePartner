package org.tidepool.carepartner.backend.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.tidepool.carepartner.backend.PersistentData
import org.tidepool.carepartner.backend.PillData
import org.tidepool.sdk.model.confirmations.Confirmation
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class DataState(
    private val dataRepository: DataRepository
): ViewModel() {
    companion object {
        const val TAG = "DataState"
        private val minDataPeriod = 1.minutes
        private val minInvitationPeriod = 1.minutes
        private val stopTimeout = 10.seconds
        
        val DATA_REPOSITORY_KEY = object : CreationExtras.Key<DataRepository> {}
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val dataRepository = this[DATA_REPOSITORY_KEY] as DataRepository
                DataState(
                    dataRepository
                )
            }
        }
    }
    
    private val _data = MutableStateFlow(emptyMap<String, PillData>())
    val data = _data.asStateFlow()
    private val _invitations = MutableStateFlow(emptyArray<Confirmation>())
    val invitations = _invitations.asStateFlow()
    
    suspend fun updateInvitations() {
        val invitations = dataRepository.getInvitations()
        if (!invitations.contentEquals(_invitations.value)) {
            _invitations.emit(invitations)
        }
    }
    
    init {
        viewModelScope.launch {
            dataRepository.setup()
            try {
                while(true) {
                    val elapsed = measureTime {
                        updateInvitations()
                    }
                    delay(minInvitationPeriod - elapsed)
                }
            } finally {
            }
        }
        viewModelScope.launch {
            dataRepository.setup()
            val map = mutableMapOf<String, PillData>()
            try {
                while(true) {
                    val elapsed = measureTime {
                        if (PersistentData.lastEmail == null) {
                            dataRepository.saveEmail()
                        }
                        val newData = dataRepository.getFoloweeData()
                        map.putAll(newData)
                        // make a copy of the map
                        _data.emit(HashMap(map))
                    }
                    delay(minDataPeriod - elapsed)
                }
            } finally {
                map.clear()
            }
        }
    }
    
    suspend fun updateData() {
        val oldData = HashMap(_data.value)
        oldData.putAll(dataRepository.getFoloweeData())
        _data.emit(oldData)
    }
    
    suspend fun acceptConfirmation(confirmation: Confirmation) {
        dataRepository.acceptConfirmation(confirmation)
        updateInvitations()
        updateData()
    }
    suspend fun rejectConfirmation(confirmation: Confirmation) {
        dataRepository.acceptConfirmation(confirmation)
        updateInvitations()
    }
}