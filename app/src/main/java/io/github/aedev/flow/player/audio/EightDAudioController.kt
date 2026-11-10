package io.github.aedev.flow.player.audio

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.AudioSettingsPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one 8D spatial audio state shared by music, video and their settings surfaces.
 *
 * Every player owns its own [Spatial8DAudioProcessor] and applies [config] to it, because a
 * processor carries filter and delay history and must never be shared by two live players.
 */
object EightDAudioController {
    private const val TAG = "EightDAudioController"

    data class Config(
        val enabled: Boolean = false,
        val cycleDurationSec: Float = 14.0f,
        val depth: Float = 0.75f,
        val roomPreset: EightDRoomPreset = EightDRoomPreset.LIVING_ROOM,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistence: AudioSettingsPersistence? = null
    private var isInitialized = false

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _speed = MutableStateFlow(14.0f)
    val speed: StateFlow<Float> = _speed

    private val _depth = MutableStateFlow(0.75f)
    val depth: StateFlow<Float> = _depth

    private val _roomPreset = MutableStateFlow(EightDRoomPreset.LIVING_ROOM)
    val roomPreset: StateFlow<EightDRoomPreset> = _roomPreset

    val config: StateFlow<Config> =
        combine(_enabled, _speed, _depth, _roomPreset) { enabled, speed, depth, room ->
            Config(enabled, speed, depth, room)
        }.stateIn(scope, SharingStarted.Eagerly, Config())

    fun initialize(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            isInitialized = true
            val store = AudioSettingsPersistence.getInstance(context.applicationContext)
            persistence = store
            scope.launch {
                runCatching {
                    val settings = store.settingsFlow.first()
                    _enabled.value = settings.eightDEnabled
                    _speed.value = settings.eightDSpeed
                    _depth.value = settings.eightDDepth
                    _roomPreset.value = EightDRoomPreset.fromString(settings.eightDRoomPreset)
                }.onFailure { Log.e(TAG, "Failed to restore 8D audio state", it) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        scope.launch { persistence?.saveEightDEnabled(enabled) }
    }

    fun setSpeed(speedSec: Float) {
        _speed.value = speedSec
        scope.launch { persistence?.saveEightDSpeed(speedSec) }
    }

    fun setDepth(depth: Float) {
        _depth.value = depth
        scope.launch { persistence?.saveEightDDepth(depth) }
    }

    fun setRoomPreset(preset: EightDRoomPreset) {
        _roomPreset.value = preset
        scope.launch { persistence?.saveEightDRoomPreset(preset.name) }
    }
}
