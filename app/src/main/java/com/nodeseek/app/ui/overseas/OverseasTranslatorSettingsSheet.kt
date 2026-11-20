package com.nodeseek.app.ui.overseas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard

/**
 * Bottom sheet allowing users to configure Tier 1 custom OpenAI-compatible or DeepLX endpoints,
 * Tier 2 zero-config Google GTX, or Tier 3 offline VPS glossary translation + display preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverseasTranslatorSettingsSheet(
    currentConfig: OverseasTranslationConfig,
    isTesting: Boolean,
    testFeedback: String?,
    testSuccess: Boolean?,
    onDismiss: () -> Unit,
    onTestConnection: (OverseasTranslationConfig) -> Unit,
    onClearCache: () -> Unit,
    onSave: (OverseasTranslationConfig, Boolean) -> Unit,
    showTranslateDetailButton: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors

    var engineKind by rememberSaveable(currentConfig) { mutableStateOf(currentConfig.engineKind) }
    var endpointUrl by rememberSaveable(currentConfig) { mutableStateOf(currentConfig.customEndpointUrl) }
    var apiKey by rememberSaveable(currentConfig) { mutableStateOf(currentConfig.customApiKey) }
    var modelName by rememberSaveable(currentConfig) { mutableStateOf(currentConfig.customModel) }
    var autoTranslate by rememberSaveable(currentConfig) { mutableStateOf(currentConfig.autoTranslateOnRefresh) }
    var displayMode by rememberSaveable(currentConfig) { mutableStateOf(currentConfig.displayMode) }
    var showApiKey by remember { mutableStateOf(false) }

    val draftConfig = OverseasTranslationConfig(
        engineKind = engineKind,
        customEndpointUrl = endpointUrl.trim(),
        customApiKey = apiKey.trim(),
        customModel = modelName.trim().ifBlank { OverseasTranslationConfig.DEFAULT_OPENAI_MODEL },
        autoTranslateOnRefresh = autoTranslate,
        displayMode = displayMode,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = PulseArchitecturalShapes.BottomSheetTop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "详情翻译引擎配置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "用于促销详情通知翻译，支持 Google GTX 与自定义 OpenAI / DeepLX",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PulseGradientDivider()

            // Display Mode selector
            Text(
                text = "展示语言模式",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverseasDisplayMode.entries.forEach { mode ->
                    val selected = displayMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else ext.cardBorder,
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            .clickable { displayMode = mode }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Engine selection
            Text(
                text = "翻译通道",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationEngineKind.entries.forEach { kind ->
                    val selected = engineKind == kind
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.SmallChip,
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    ext.cardSurface
                                },
                                borderColor = if (selected) MaterialTheme.colorScheme.primary else ext.cardBorder,
                            )
                            .clickable {
                                engineKind = kind
                                if (endpointUrl.isBlank()) {
                                    endpointUrl = when (kind) {
                                        TranslationEngineKind.OPENAI_COMPATIBLE ->
                                            OverseasTranslationConfig.DEFAULT_OPENAI_ENDPOINT
                                        TranslationEngineKind.DEEPLX ->
                                            OverseasTranslationConfig.DEFAULT_DEEPLX_ENDPOINT
                                        else -> ""
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { engineKind = kind },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = kind.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = kind.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Tier 1 Custom Provider fields
            AnimatedVisibility(
                visible = engineKind == TranslationEngineKind.OPENAI_COMPATIBLE ||
                    engineKind == TranslationEngineKind.DEEPLX,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val isOpenAi = engineKind == TranslationEngineKind.OPENAI_COMPATIBLE
                    OutlinedTextField(
                        value = endpointUrl,
                        onValueChange = { endpointUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = {
                            Text(if (isOpenAi) "API 地址（支持 /v1 或 /v1/chat/completions）" else "DeepLX 接口地址")
                        },
                        placeholder = {
                            Text(
                                if (isOpenAi) OverseasTranslationConfig.DEFAULT_OPENAI_ENDPOINT
                                else OverseasTranslationConfig.DEFAULT_DEEPLX_ENDPOINT,
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (isOpenAi) "API Key（仅存本地）" else "访问令牌（可选）") },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showApiKey) "隐藏密钥" else "显示密钥",
                                )
                            }
                        },
                    )

                    if (isOpenAi) {
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("模型名称") },
                            placeholder = { Text(OverseasTranslationConfig.DEFAULT_OPENAI_MODEL) },
                            leadingIcon = {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }

            // Auto translate toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = PulseArchitecturalShapes.SmallChip)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "打开详情时自动附带译文",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "内容按哈希持久化缓存，已翻译过的详情不会重复消耗额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoTranslate,
                    onCheckedChange = { autoTranslate = it },
                )
            }

            // Test result feedback
            if (!testFeedback.isNullOrBlank()) {
                val isOk = testSuccess == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isOk) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = testFeedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOk) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // Test & Clear Cache row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onTestConnection(draftConfig) },
                    enabled = !isTesting,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("测试通道")
                }

                TextButton(
                    onClick = onClearCache,
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空缓存")
                }
            }

            // Save actions
            if (showTranslateDetailButton) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            onSave(draftConfig, false)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                    ) {
                        Text("保存配置")
                    }
                    Button(
                        onClick = {
                            onSave(draftConfig, true)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.25f),
                        shape = CircleShape,
                    ) {
                        Text("保存并翻译详情")
                    }
                }
            } else {
                Button(
                    onClick = {
                        onSave(draftConfig, false)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                ) {
                    Text("保存配置")
                }
            }
        }
    }
}
