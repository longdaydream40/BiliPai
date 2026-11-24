package com.android.purebilibili.feature.plugin

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.purebilibili.core.input.gamepad.GamepadConfig
import com.android.purebilibili.core.input.gamepad.GamepadControlEngine
import com.android.purebilibili.core.plugin.BuiltinPlugin
import com.android.purebilibili.core.plugin.BuiltinPluginIds
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "GamepadControlPlugin"

/**
 * 多型号手柄全局操控内置插件。
 *
 * 配置同时同步到「设置 → 手柄控制」独立模块与「插件中心」，支持 Xbox / PlayStation / Switch / 通用手柄、
 * 底部提示栏隐藏、虚拟光标外观与自定义键位映射。
 */
class GamepadControlPlugin : BuiltinPlugin {

    override val id: String = BuiltinPluginIds.GAMEPAD_CONTROL
    override val name: String = "手柄控制"
    override val description: String = "支持 Xbox / PlayStation / Switch 等手柄双摇杆操控、隐藏提示栏与自定义键位"
    override val version: String = "2.0.0"
    override val author: String = "BiliPai项目组"
    override val icon: ImageVector = Icons.Outlined.SportsEsports

    override suspend fun onEnable() {
        loadPersistedConfig(PluginManager.getContext())
        GamepadControlEngine.setConfigPersistCallback { updated ->
            commitConfig(PluginManager.getContext(), updated)
        }
        GamepadControlEngine.setPluginEnabled(true)
        Logger.d(TAG, "手柄控制插件已启用")
    }

    override suspend fun onDisable() {
        GamepadControlEngine.setPluginEnabled(false)
        Logger.d(TAG, "手柄控制插件已禁用")
    }

    internal fun snapshotConfig(): GamepadConfig = GamepadControlEngine.configFlow.value

    internal suspend fun loadConfigSuspend() {
        loadPersistedConfig(PluginManager.getContext())
    }

    internal fun commitConfig(newConfig: GamepadConfig) {
        commitConfig(PluginManager.getContext(), newConfig)
    }

    @Composable
    override fun SettingsContent() {
        SettingsContent(Modifier.fillMaxWidth())
    }

    @Composable
    override fun SettingsContent(modifier: Modifier) {
        GamepadControlSettings(plugin = this, modifier = modifier)
    }

    companion object {
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        suspend fun loadPersistedConfig(context: Context): GamepadConfig {
            return try {
                val raw = PluginStore.getConfigJson(context.applicationContext, BuiltinPluginIds.GAMEPAD_CONTROL)
                val parsed = if (!raw.isNullOrBlank()) {
                    json.decodeFromString<GamepadConfig>(raw)
                } else {
                    GamepadConfig()
                }
                GamepadControlEngine.updateConfig(parsed)
                parsed
            } catch (e: Exception) {
                Logger.e(TAG, "加载手柄控制配置失败", e)
                GamepadControlEngine.configFlow.value
            }
        }

        fun commitConfig(context: Context, newConfig: GamepadConfig) {
            GamepadControlEngine.updateConfig(newConfig)
            val appContext = context.applicationContext
            ioScope.launch {
                try {
                    PluginStore.setConfigJson(
                        context = appContext,
                        pluginId = BuiltinPluginIds.GAMEPAD_CONTROL,
                        configJson = json.encodeToString(newConfig),
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "保存手柄控制配置失败", e)
                }
            }
        }
    }
}
