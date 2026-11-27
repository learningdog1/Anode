package com.nodeseek.app.data.alerts

import android.content.Context
import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.data.local.SettingsSecretCipherFactory
import com.nodeseek.app.domain.alerts.AlertEventCategory
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.BarkForwardConfig
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.alerts.WebhookForwardConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Atomic JSON store for external alert forwarding configuration, encrypting all sensitive
 * channel credentials (`botToken`, `deviceKey`, `webhookUrl`, `webhookSecretValue`) via
 * [SettingsSecretCipher] (`AES/GCM/NoPadding`). Never touched by plaintext v1 backups.
 */
class AlertForwardingStore(
    private val storeFile: File,
    private val secretCipher: SettingsSecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _configFlow = MutableStateFlow(AlertForwardingConfig())
    val configFlow: StateFlow<AlertForwardingConfig> = _configFlow.asStateFlow()

    @Volatile
    private var loaded: Boolean = false

    suspend fun ensureLoaded(): AlertForwardingConfig {
        if (loaded) return _configFlow.value
        return mutex.withLock {
            if (loaded) return@withLock _configFlow.value
            val initial = readFromDisk()
            _configFlow.value = initial
            loaded = true
            initial
        }
    }

    suspend fun updateConfig(transform: (AlertForwardingConfig) -> AlertForwardingConfig): AlertForwardingConfig {
        return mutex.withLock {
            val current = if (loaded) {
                _configFlow.value
            } else {
                readFromDisk().also { loaded = true }
            }
            val updated = transform(current)
            writeToDisk(updated)
            _configFlow.value = updated
            updated
        }
    }

    private suspend fun readFromDisk(): AlertForwardingConfig = withContext(ioDispatcher) {
        if (!storeFile.isFile) return@withContext AlertForwardingConfig()
        val raw = runCatching { storeFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext AlertForwardingConfig()
        val dto = runCatching { json.decodeFromString<PersistedAlertForwardingDto>(raw) }.getOrNull()
            ?: return@withContext AlertForwardingConfig()
        val domain = dto.toDomain(secretCipher)
        if (dto.webhookCustomHeaders.isNotEmpty()) {
            // Migrate legacy plaintext custom headers on disk to encrypted values transparently.
            runCatching { writeToDisk(domain) }
        }
        domain
    }

    private suspend fun writeToDisk(config: AlertForwardingConfig) = withContext(ioDispatcher) {
        val dto = PersistedAlertForwardingDto.fromDomain(config, secretCipher)
        val encoded = json.encodeToString(dto).toByteArray(Charsets.UTF_8)
        storeFile.parentFile?.mkdirs()
        val tmpFile = File(storeFile.parentFile, "${storeFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(encoded)
            fos.fd.sync()
        }
        if (!tmpFile.renameTo(storeFile)) {
            FileOutputStream(storeFile).use { fos ->
                fos.write(encoded)
                fos.fd.sync()
            }
            tmpFile.delete()
        }
    }

    @Serializable
    private data class PersistedAlertForwardingDto(
        val schemaVersion: Int = 1,
        val enabled: Boolean = false,
        val enabledEvents: Set<AlertEventCategory> = AlertEventCategory.entries.toSet(),
        val telegramEnabled: Boolean = false,
        val telegramBotTokenEncrypted: String = "",
        val telegramChatId: String = "",
        val telegramDisableNotification: Boolean = false,
        val barkEnabled: Boolean = false,
        val barkServerUrl: String = BarkForwardConfig.DEFAULT_BARK_SERVER_URL,
        val barkDeviceKeyEncrypted: String = "",
        val barkSound: String = "",
        val barkGroup: String = "Anode",
        val barkIsArchive: Boolean = true,
        val webhookEnabled: Boolean = false,
        val webhookUrlEncrypted: String = "",
        val webhookSecretHeaderName: String = WebhookForwardConfig.DEFAULT_SECRET_HEADER,
        val webhookSecretValueEncrypted: String = "",
        // Legacy plaintext field retained solely for one-way transparent migration on read; always persisted empty.
        val webhookCustomHeaders: Map<String, String> = emptyMap(),
        val webhookCustomHeadersEncrypted: Map<String, String> = emptyMap(),
    ) {
        fun toDomain(cipher: SettingsSecretCipher): AlertForwardingConfig {
            val decryptedCustomHeaders = buildMap<String, String> {
                webhookCustomHeaders.forEach { (k, v) ->
                    val cleanKey = k.trim()
                    if (cleanKey.isNotEmpty() && v.isNotEmpty()) {
                        put(cleanKey, v)
                    }
                }
                webhookCustomHeadersEncrypted.forEach { (k, encVal) ->
                    val cleanKey = k.trim()
                    val plain = cipher.decrypt(encVal)
                    if (cleanKey.isNotEmpty() && plain.isNotEmpty()) {
                        put(cleanKey, plain)
                    }
                }
            }
            return AlertForwardingConfig(
                enabled = enabled,
                enabledEvents = enabledEvents,
                telegram = TelegramForwardConfig(
                    enabled = telegramEnabled,
                    botToken = cipher.decrypt(telegramBotTokenEncrypted),
                    chatId = telegramChatId,
                    disableNotification = telegramDisableNotification,
                ),
                bark = BarkForwardConfig(
                    enabled = barkEnabled,
                    serverUrl = barkServerUrl.ifBlank { BarkForwardConfig.DEFAULT_BARK_SERVER_URL },
                    deviceKey = cipher.decrypt(barkDeviceKeyEncrypted),
                    sound = barkSound,
                    group = barkGroup.ifBlank { "Anode" },
                    isArchive = barkIsArchive,
                ),
                webhook = WebhookForwardConfig(
                    enabled = webhookEnabled,
                    url = cipher.decrypt(webhookUrlEncrypted),
                    secretHeaderName = webhookSecretHeaderName.ifBlank { WebhookForwardConfig.DEFAULT_SECRET_HEADER },
                    secretValue = cipher.decrypt(webhookSecretValueEncrypted),
                    customHeaders = decryptedCustomHeaders,
                ),
            )
        }

        companion object {
            fun fromDomain(
                config: AlertForwardingConfig,
                cipher: SettingsSecretCipher,
            ): PersistedAlertForwardingDto = PersistedAlertForwardingDto(
                enabled = config.enabled,
                enabledEvents = config.enabledEvents,
                telegramEnabled = config.telegram.enabled,
                telegramBotTokenEncrypted = cipher.encrypt(config.telegram.botToken),
                telegramChatId = config.telegram.chatId.trim(),
                telegramDisableNotification = config.telegram.disableNotification,
                barkEnabled = config.bark.enabled,
                barkServerUrl = config.bark.serverUrl.trim().ifBlank { BarkForwardConfig.DEFAULT_BARK_SERVER_URL },
                barkDeviceKeyEncrypted = cipher.encrypt(config.bark.deviceKey),
                barkSound = config.bark.sound.trim(),
                barkGroup = config.bark.group.trim().ifBlank { "Anode" },
                barkIsArchive = config.bark.isArchive,
                webhookEnabled = config.webhook.enabled,
                webhookUrlEncrypted = cipher.encrypt(config.webhook.url),
                webhookSecretHeaderName = config.webhook.secretHeaderName.trim()
                    .ifBlank { WebhookForwardConfig.DEFAULT_SECRET_HEADER },
                webhookSecretValueEncrypted = cipher.encrypt(config.webhook.secretValue),
                webhookCustomHeaders = emptyMap(),
                webhookCustomHeadersEncrypted = config.webhook.customHeaders.entries
                    .mapNotNull { (k, v) ->
                        val cleanKey = k.trim()
                        if (cleanKey.isEmpty() || v.isEmpty()) null else cleanKey to cipher.encrypt(v)
                    }
                    .toMap(),
            )
        }
    }

    companion object {
        const val STORE_FILE_NAME = "alert_forwarding_config.json"

        fun forContext(context: Context): AlertForwardingStore {
            val appContext = context.applicationContext ?: context
            return AlertForwardingStore(
                storeFile = File(appContext.filesDir, STORE_FILE_NAME),
                secretCipher = SettingsSecretCipherFactory.createDefault(appContext),
            )
        }
    }
}
