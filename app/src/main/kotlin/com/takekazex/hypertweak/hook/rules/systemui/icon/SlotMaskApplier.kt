package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierMask
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftPanelMotion
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.*
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 輕量化版本：移除所有 deoptimize() 與 onMeasure / onLayout 高頻攔截，
 * 僅透過原生 setIgnoredSlots / addIgnoredSlots 實現「狀態欄隱藏圖標、控制中心顯示」。
 */
internal object SlotMaskApplier : IconRenderer() {
    override val hostCallbackOrder = 10
    private val settingCcIconRowOverrides by hookSetting(emptySet()) {
        Preferences.iconConfig().controlCenter.iconRowOverrides
    }

    init {
        hostRecovery(HostRecoveryPhase.CONTAINERS, icons = true) {
            recoverExistingContainers(it.views)
        }
    }

    private const val TAG = "IconTuner"
    private const val LIST_FACTORY_CLASS =
        "com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule_ProvideStatusBarIconListFactory"
    private const val LIST_CLASS = "com.android.systemui.statusbar.phone.ui.StatusBarIconList"
    private const val SLOT_CLASS = "com.android.systemui.statusbar.phone.ui.StatusBarIconList\$Slot"
    private const val CONTAINER_CLASS = "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    private const val NETWORK_SPEED_CLASS = "com.android.systemui.statusbar.views.NetworkSpeedView"
    private const val CONTROL_CENTER_ROW_CLASS =
        "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon"
    private const val CONTROL_CENTER_FAKE_ROW_CLASS =
        "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons"

    private data class ContainerState(
        var hostIgnored: List<String>,
        var lastApplied: List<String>? = null,
        val nativeVisibleStates: WeakHashMap<View, Int> = WeakHashMap()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val containerStates = generationMainResource(WeakHashMap<Any, ContainerState>()) { it.clear() }
    private val currentConfig by hookSetting(IconConfigDecoder.decode { null }) { Preferences.iconConfig() }
    private val mobilePolicy by hookSetting(NetworkIconVisibility()) { IconTunerOptions.networkVisibility() }
    private val maskOwners = SlotClaimRegistry { currentConfig }
    private val duoSurfaces = generationMainResource(WeakHashMap<Any, PlanSurface>()) { it.clear() }
    private val duoSlots = IconSlotParts.NETWORK_SLOTS + "airplane"

    private var options by generationState { IconTunerOptions.snapshot() }
    private var restoring by generationState { false }
    @Volatile
    private var retiring = false
    init { generationCleanup { retiring = true } }

    private var rowOverrides: Set<String> by generationState { emptySet() }
    private var originalSlotOrder by generationState { emptyList<String>() }
    private var slotsField: Field? by generationState { null }
    private var slotNameField: Field? by generationState { null }
    private var ignoredSlotsField: Field? by generationState { null }
    private var layoutFromField: Field? by generationState { null }
    private var slotConstructor: Constructor<*>? by generationState { null }

    internal val typeOnlyInstalled get() = false
    private var stackedPublished by generationState { false }
    internal fun stackedReady(ready: Boolean) { stackedPublished = ready }
    internal fun releaseStacked() = stackedReady(false)

    override fun saveHotReloadState(): Any = HostAccess.onMain {
        mapOf("order" to ArrayList(originalSlotOrder), "containers" to synchronized(stateLock) {
            containerStates.entries.map { (container, state) -> listOf(container, ArrayList(state.hostIgnored)) }
        })
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = (state as? Map<*, *>)?.get("containers") as? List<*> ?: state as? List<*> ?: return
        HostAccess.onMain {
            originalSlotOrder = ((state as? Map<*, *>)?.get("order") as? List<*>)?.filterIsInstance<String>().orEmpty()
            saved.forEach { entry ->
                val row = entry as? List<*> ?: return@forEach
                val container = row.getOrNull(0) as? View ?: return@forEach
                val ignored = (row.getOrNull(1) as? List<*>)?.filterIsInstance<String>() ?: return@forEach
                synchronized(stateLock) { containerStates[container] = ContainerState(ignored) }
            }
        }
    }

    internal fun recoverSlotOrder(controller: Any, context: Context) {
        // 保持原生槽位順序，避免重寫導致的高額重綁開銷
    }

    internal fun refreshPolicy() {
        if (retiring) return
        options = IconTunerOptions.snapshot()
        rowOverrides = settingCcIconRowOverrides
        val tracked = synchronized(stateLock) { containerStates.entries.map { it.key to it.value } }
        tracked.forEach { (container, state) ->
            runCatching {
                val merged = blockedFor(container, surfaceFor(container, state.hostIgnored), state.hostIgnored)
                if (merged != state.lastApplied) {
                    state.lastApplied = merged
                    restoreIgnoredSlots(container, merged)
                    (container as? View)?.requestLayout()
                }
            }.onFailure { DebugLog.w(TAG, "live icon policy refresh failed", it) }
        }
    }

    internal fun recoverExistingContainers(views: List<View>) {
        views.filter { it.javaClass.name == CONTAINER_CLASS }.forEach { container ->
            runCatching {
                val state = synchronized(stateLock) { containerStates[container] }
                    ?: captureContainerState(container, ignoredSlotsField?.get(container) as? List<*> ?: return@runCatching)
                val merged = blockedFor(container, surfaceFor(container, state.hostIgnored), state.hostIgnored)
                state.lastApplied = merged
                restoreIgnoredSlots(container, merged)
                container.requestLayout()
            }.onFailure { DebugLog.w(TAG, "hot reload container recovery failed", it) }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        mainHandler.removeCallbacksAndMessages(null)
        val pending = synchronized(stateLock) { containerStates.entries.map { it.key to it.value } }
        HostAccess.onMain {
            restoring = true
            maskOwners.clear()
            try {
                pending.forEach { (container, state) ->
                    restoreIgnoredSlots(container, state.hostIgnored.toList())
                    (container as? View)?.requestLayout()
                }
            } finally {
                restoring = false
                synchronized(stateLock) { containerStates.clear() }
            }
        }
    }

    override fun onHook() {
        retiring = false
        options = IconTunerOptions.snapshot()
        rowOverrides = settingCcIconRowOverrides
        // 只 Hook 容器的 ignoredSlots 設定方法，徹底移除 deoptimize()、onMeasure、onLayout 攔截！
        hookIgnoredSlots()
        DebugLog.i(TAG, "Lightweight SlotMaskApplier hooks installed (No deoptimize / No onLayout)")
    }

    private fun hookIgnoredSlots() {
        val containerClass = CONTAINER_CLASS.toClassOrNull()
        if (containerClass == null) {
            DebugLog.hookSkipped(TAG, CONTAINER_CLASS, "class not found")
            return
        }
        ignoredSlotsField = findField(containerClass, "ignoredSlots")
        layoutFromField = findField(containerClass, "_layoutFrom")
        if (ignoredSlotsField == null) {
            DebugLog.hookSkipped(TAG, "$CONTAINER_CLASS#ignoredSlots", "field not found")
            return
        }
        hookContainerMethod(containerClass, "setIgnoredSlots")
        hookContainerMethod(containerClass, "addIgnoredSlots")
    }

    private fun hookContainerMethod(containerClass: Class<*>, methodName: String) {
        val method = findMethod(containerClass, methodName) ?: run {
            DebugLog.hookSkipped(TAG, "$CONTAINER_CLASS#$methodName", "method not found")
            return
        }
        method.observe {
            before { param ->
                if (restoring || retiring) return@before
                val incoming = param.args.getOrNull(0) as? List<*> ?: return@before
                val state = captureContainerState(param.thisObject, incoming)
                val surface = surfaceFor(param.thisObject, incoming)
                val merged = blockedFor(param.thisObject, surface, state.hostIgnored)
                state.lastApplied = merged
                param.args[0] = ArrayList(merged)
            }
            after { param ->
                if (restoring || retiring) return@after
                synchronized(stateLock) {
                    containerStates[param.thisObject]?.lastApplied?.let { applied ->
                        restoreIgnoredSlots(param.thisObject, applied)
                    }
                }
            }
        }
    }

    private fun captureContainerState(container: Any, incoming: List<*>): ContainerState {
        val incomingStrings = stableStrings(incoming)
        synchronized(stateLock) {
            val existing = containerStates[container]
            val host = IconManagerHooker.pristineFor(incoming) ?: run {
                val masks = maskSlotsFor(container)
                val echoedOurOwnList = existing != null &&
                    (existing.lastApplied == incomingStrings ||
                        (masks.isNotEmpty() &&
                            (existing.lastApplied.orEmpty() + masks).distinct() == incomingStrings))
                if (echoedOurOwnList) existing.hostIgnored else incomingStrings
            }
            return (existing ?: ContainerState(host)).also {
                it.hostIgnored = host
                containerStates[container] = it
            }
        }
    }

    private fun maskSlotsFor(container: Any): List<String> =
        planSurfaceFor(container)?.let { maskOwners.slots(container, it) }.orEmpty()

    private fun planSurfaceFor(container: Any): PlanSurface? = duoSurfaces[container] ?: when (
        runCatching { layoutFromField?.getInt(container) }.getOrNull()
    ) {
        0 -> PlanSurface.STATUS_BAR
        1 -> PlanSurface.KEYGUARD
        5 -> PlanSurface.CONTROL_CENTER_COLLAPSED
        6 -> PlanSurface.CONTROL_CENTER_EXPANDED
        else -> null
    }

    private fun surfaceFor(container: Any, incoming: List<*>): IconSurface {
        IconManagerHooker.surfaceFor(incoming)?.let { return it }
        val layoutFrom = runCatching { layoutFromField?.get(container) as? Int }.getOrNull()
        return when (layoutFrom) {
            0, 1 -> IconSurface.STATUS_BAR
            4, 5, 6 -> IconSurface.CONTROL_CENTER
            else -> IconSurface.UNKNOWN
        }
    }

    private fun blockedFor(container: Any, surface: IconSurface, systemSlots: List<String>): List<String> {
        return IconSlotPolicy.blockedFor(surface, systemSlots, options.policy)
    }

    private fun restoreIgnoredSlots(container: Any, values: List<String>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { restoreIgnoredSlots(container, values) }
            return
        }
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val ignored = ignoredSlotsField?.get(container) as? MutableList<Any?> ?: return
            ignored.clear()
            ignored.addAll((values + maskSlotsFor(container)).distinct())
        }.onFailure { DebugLog.w(TAG, "ignoredSlots restore failed", it) }
    }

    fun setDuoMask(container: Any, active: Boolean, surface: PlanSurface? = null): Boolean = false
    internal fun duoOwnsAirplane(container: Any): Boolean = false
    internal fun setCarrierMask(container: Any, mask: CarrierMask): Boolean = false
    internal fun setAirplaneLabelMask(container: Any, active: Boolean): Boolean = false
    internal fun updateLowerRowHandover(real: ViewGroup, fake: ViewGroup, progress: Float) {}
    internal fun clearLowerRowHandover() {}

    private fun findField(type: Class<*>, name: String): Field? = HostAccess.field(type, name)

    private fun findMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val matches = current.declaredMethods.filter {
                it.name == name && it.parameterTypes.size == 1
            }
            if (matches.size == 1) return matches.single().apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }

    private fun stableStrings(values: List<*>): List<String> {
        val result = LinkedHashSet<String>()
        values.forEach { value ->
            if (value is String && value.isNotBlank()) result += value
        }
        return result.toList()
    }
}
