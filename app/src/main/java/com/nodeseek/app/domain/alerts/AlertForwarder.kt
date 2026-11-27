package com.nodeseek.app.domain.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AlertForwarder {
    val configFlow: StateFlow<AlertForwardingConfig>

    suspend fun updateConfig(transform: (AlertForwardingConfig) -> AlertForwardingConfig)

    suspend fun forward(event: ForwardableAlertEvent): List<ChannelDeliveryResult>

    suspend fun sendTestAlert(): List<ChannelDeliveryResult>
}

object NoOpAlertForwarder : AlertForwarder {
    private val _configFlow = MutableStateFlow(AlertForwardingConfig())
    override val configFlow: StateFlow<AlertForwardingConfig> = _configFlow.asStateFlow()

    override suspend fun updateConfig(transform: (AlertForwardingConfig) -> AlertForwardingConfig) = Unit

    override suspend fun forward(event: ForwardableAlertEvent): List<ChannelDeliveryResult> = emptyList()

    override suspend fun sendTestAlert(): List<ChannelDeliveryResult> = emptyList()
}
