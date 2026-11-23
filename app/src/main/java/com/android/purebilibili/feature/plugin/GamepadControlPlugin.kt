package com.android.purebilibili.feature.plugin

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
 * Xbox / 通用手柄全局操控内置插件。
 *
 * 在插件中心提供开关、灵敏度、死区、倍速、键位提示 HUD 以及实时摇杆/按键测试仪。
 */
class GamepadControlPlugin : BuiltinPlugin {

    override val id: String = BuiltinPluginIds.GAMEPAD_CONTROL
    override val name: String = "手柄操控"
    override val description: String = "支持 Xbox 等手柄双摇杆光标/滚动、A 确认拖拽、B 回退及播放快捷操控"
    override val version: String = "1.0.0"
    override val author: String = "BiliPai项目组"
    override val icon: ImageVector = Icons.Outlined.SportsEsports

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var config: GamepadConfig = GamepadConfig()

    override suspend fun onEnable() {
        loadConfigSuspend()
        GamepadControlEngine.updateConfig(config)
        GamepadControlEngine.setPluginEnabled(true)
        Logger.d(TAG, "手柄操控插件已启用")
    }

    override suspend fun onDisable() {
        GamepadControlEngine.setPluginEnabled(false)
        Logger.d(TAG, "手柄操控插件已禁用")
    }

    internal fun snapshotConfig(): GamepadConfig = config

    internal suspend fun loadConfigSuspend() {
        try {
            val context = PluginManager.getContext()
            val raw = PluginStore.getConfigJson(context, id)
            if (!raw.isNullOrBlank()) {
                config = json.decodeFromString<GamepadConfig>(raw)
            }
            GamepadControlEngine.updateConfig(config)
        } catch (e: Exception) {
            Logger.e(TAG, "加载手柄操控配置失败", e)
        }
    }

    internal fun commitConfig(newConfig: GamepadConfig) {
        config = newConfig
        GamepadControlEngine.updateConfig(newConfig)
        ioScope.launch {
            try {
                val context = PluginManager.getContext()
                PluginStore.setConfigJson(context, id, json.encodeToString(newConfig))
            } catch (e: Exception) {
                Logger.e(TAG, "保存手柄操控配置失败", e)
            }
        }
    }

    @Composable
    override fun SettingsContent() {
        SettingsContent(Modifier.fillMaxWidth())
    }

    @Composable
    override fun SettingsContent(modifier: Modifier) {
        GamepadControlSettings(plugin = this, modifier = modifier)
    }
}
