package com.pirorin215.btclockmob.viewModel

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

enum class BleOperation {
    IDLE,
    FETCHING_SETTINGS,
    SENDING_SETTINGS,
    SENDING_TIME
}
