package com.pirorin215.btclockmob.viewModel

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

enum class BleOperation {
    IDLE,
    SENDING_TIME
}
