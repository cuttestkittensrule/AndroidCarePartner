package org.tidepool.carepartner.backend.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
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
        private val minDataPeriod = 5.minutes
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
    
    private val _data = flow {
        dataRepository.setup()
        val map = mutableMapOf<String, PillData>()
        try {
            while(true) {
                val elapsed = measureTime {
                    val newData = dataRepository.getFoloweeData()
                    map.putAll(newData)
                    // make a copy of the map
                    emit(HashMap(map))
                }
                delay(minDataPeriod - elapsed)
            }
        } finally {
            map.clear()
        }
    }
    val data = _data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), mapOf())
    private val _invitations = flow {
        dataRepository.setup()
        var lastInvite: Array<Confirmation>? = null
        try {
            while(true) {
                val elapsed = measureTime {
                    val invitations = dataRepository.getInvitations()
                    if (!invitations.contentEquals(lastInvite))  {
                        emit(arrayOf(*invitations))
                        lastInvite = invitations
                    }
                }
                delay(minInvitationPeriod - elapsed)
            }
        } finally {
            lastInvite = null
        }
    }
    val invitations = _invitations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), emptyArray())
}