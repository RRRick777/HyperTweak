package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.bootstrap.AppHostRecovery
import com.takekazex.hypertweak.hook.bootstrap.SystemUiSettingsHost
import com.takekazex.hypertweak.hook.nativebridge.NativeRuleStatePublisher
import com.takekazex.hypertweak.hook.preferences.RuntimeSettingsPreferences
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPackageState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPluginState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadTargetState
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.runtime.settings.SettingsReloadDispatcher
import com.takekazex.hypertweak.hook.bootstrap.PackageHookPlan
import com.takekazex.hypertweak.hook.bootstrap.ProcessLifecycleHooks
import com.takekazex.hypertweak.hook.bootstrap.RuntimeSettingsParticipants
import com.takekazex.hypertweak.hook.bootstrap.SystemServerHookPlan
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.hook.bootstrap.ProcessLogging
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HookEntry : XposedModule() {
    private companion object {
        const val LAUNCHER_PACKAGE = "com.miui.home"
        const val KEY_HEADER_CLOCK_SECONDS = "notification_header_clock_seconds"

        /**
         * 只允許掛載與你 4 個目標功能相關的輕量級 Hooker，徹底阻斷高耗電 UI 渲染及無關進程 Hook
         */
        private val ALLOWED_SYSTEMUI_HOOKERS = setOf(
            "IconHostHooks",
            "IconManagerHooker",
            "SlotMaskApplier",
            "HideFingerprintIcon",
            "HideFingerprintHooker",
            "LockscreenHooker",
            "KeyguardHooker",
            "NotificationClockSecondsHooker",
            "ClockSecondsHooker",
            "NotificationHeaderHooker",
            "StatusBarClockHooker",
            "RestartBroadcastHooker"
        )
    }

    private fun shouldAttachHooker(hooker: BaseHooker, ctx: ModuleContext): Boolean {
        if (ctx.isSystemServer) return false
        val pkg = ctx.packageName
        if (pkg != "com.android.systemui" && pkg != LAUNCHER_PACKAGE) return false

        val name = hooker::class.java.simpleName
        val fullName = hooker::class.java.name

        if (pkg == LAUNCHER_PACKAGE) {
            // 桌面進程：攔截手勢橋接與底欄動畫，保留基礎與最近任務震動相關 Hook
            if (name.contains("BackGesture", ignoreCase = true) ||
                name.contains("HideBottomBar", ignoreCase = true) ||
                name.contains("Icon", ignoreCase = true)
            ) return false
            return true
        }

        // SystemUI 進程：白名單精準放行（鎖屏指紋、狀態欄圖標隱藏/控制中心顯示、顯秒）
        if (name in ALLOWED_SYSTEMUI_HOOKERS) return true
        if (name.contains("Fingerprint", ignoreCase = true) && !name.contains("Avoid", ignoreCase = true)) return true
        if (name.contains("Clock", ignoreCase = true) && !name.contains("Island", ignoreCase = true)) return true
        if (fullName.contains("notification_header", ignoreCase = true)) return true

        DebugLog.d("HookEntry", "Skipped heavy hooker=$name in package=$pkg")
        return false
    }

    private val MAX_PREFS_INIT_RETRIES = 4
    private val PREFS_RETRY_DELAY_MS = 500L

    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private val rootHookers = ConcurrentHashMap.newKeySet<BaseHooker>()
    private val packageStates = ConcurrentHashMap<String, HotReloadPackageState>()
    private val pendingAppContextPackages = ConcurrentHashMap.newKeySet<String>()
    private val preferenceRetryGeneration = AtomicLong(0L)
    @Volatile private var nativeRuleStatePublisher: NativeRuleStatePublisher? = null
    @Volatile private var generationRetired = false
    @Synchronized private fun nativePublisher(): NativeRuleStatePublisher? {
        if (generationRetired || processName != "com.android.systemui") return null
        return nativeRuleStatePublisher ?: NativeRuleStatePublisher().also { nativeRuleStatePublisher = it }
    }
    private val runtimeSettingsReceiver by lazy {
        RuntimeSettingsReceiver(object : (Set<String>, Set<String>) -> Unit {
            override fun invoke(groups: Set<String>, keys: Set<String>) = requestSettingsReload(groups, keys)
        }, upstreamPackageChanged = null)
    }
    private val systemUiSettings = SystemUiSettingsHost(
        { processName }, { generationRetired }, { packageStates["com.android.systemui"] }, { rootHookers }
    )
    private val iconSettingsLock = java.util.concurrent.locks.ReentrantLock()
    private val settingsHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val settingsReload by lazy {
        SettingsReloadDispatcher(
            post = { task, delay -> settingsHandler.postDelayed(task, delay) },
            remove = { settingsHandler.removeCallbacks(it) },
            failure = { id, error -> DebugLog.w("RuntimeSettings", "reload failed participant=$id", error) }
        ).apply {
            RuntimeSettingsParticipants.install(this, processName, isSystemServer,
                RuntimeSettingsParticipants.Actions(
                    icons = { keys ->
                        if (!iconSettingsLock.tryLock()) false else try { systemUiSettings.applyLiveIconSettings(keys) }
                        finally { iconSettingsLock.unlock() }
                    },
                    publishNative = { publishNativeRuleSwitches() },
                    aod = { systemUiSettings.applyLiveAodSettings() },
                    header = { systemUiSettings.applyHeaderSettings(it) },
                    notificationText = { systemUiSettings.applyNotificationTextSettings(it) },
                    hookers = { rootHookers.toList() },
                    retired = { generationRetired },
                    ime = { refreshImeSettings() }
                )
            )
        }
    }
    private fun requestSettingsReload(groups: Set<String>, keys: Set<String> = emptySet()) {
        if (groups.isEmpty()) return
        settingsHandler.post {
            if (!generationRetired) settingsReload.request(
                groups.intersect(RuntimeSettingsGroups.forProcess(processName, isSystemServer)), keys
            )
        }
    }
    private fun cancelSettingsReload() {
        if (android.os.Looper.myLooper() == settingsHandler.looper) settingsReload.cancel() else {
            val done = java.util.concurrent.CountDownLatch(1)
            settingsHandler.post { try { settingsReload.cancel() } finally { done.countDown() } }
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "settings reload retirement timed out" }
        }
    }
    private fun resumeSettingsReload() { settingsHandler.post { settingsReload.resume() } }

    private val runtimeContextRetry by lazy {
        com.takekazex.hypertweak.hook.base.SystemServerStartupRetry("RuntimeSettings", action = {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = owner.getMethod("currentActivityThread").invoke(null) ?: return@SystemServerStartupRetry false
            val context = owner.getMethod("getSystemContext").invoke(thread) as? Context ?: return@SystemServerStartupRetry false
            attachRuntimeSettingsContext(context)
            true
        })
    }
    private fun attachRuntimeSettingsContext(context: Context) {
        if (!generationRetired && RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) runtimeSettingsReceiver.attach(context)
    }

    @Volatile private var runtimePreferencesSource: SharedPreferences? = null
    @Volatile private var remoteSettingsReady = false
    private val callbackSnapshotRefresh = Runnable {
        if (!generationRetired) {
            com.takekazex.hypertweak.hook.runtime.settings.HookSettingsSnapshots.refresh()
            DebugLog.controlsChanged()
        }
    }
    private val runtimePreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!generationRetired) {
            Preferences.invalidateRuntimeReadCache()
            settingsHandler.removeCallbacks(callbackSnapshotRefresh)
            settingsHandler.postDelayed(callbackSnapshotRefresh, 150L)
            if (key == null || key == RuntimeSettingsGroups.REVISION || RuntimeSettingsGroups.owns(key))
                runtimeSettingsReceiver.resync()
        }
    }

    private fun detachRuntimePreferencesListener() {
        settingsHandler.removeCallbacks(callbackSnapshotRefresh)
        runtimePreferencesSource?.let { source ->
            runCatching { source.unregisterOnSharedPreferenceChangeListener(runtimePreferencesListener) }
        }
        runtimePreferencesSource = null
    }

    private fun bindRuntimePreferencesListener(source: SharedPreferences) {
        if (runtimePreferencesSource === source) return
        detachRuntimePreferencesListener()
        runCatching {
            source.registerOnSharedPreferenceChangeListener(runtimePreferencesListener)
            runtimePreferencesSource = source
        }.onFailure { DebugLog.w("NativeRules", "runtime settings listener unavailable", it) }
    }
    private var skipGoogleProcess = false
    private lateinit var processName: String
    private var isSystemServer: Boolean = false
    private var systemServerClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        // 只處理 SystemUI 與小米桌面，其餘進程直接跳過以節省記憶體與 CPU
        skipGoogleProcess = isSystemServer || (processName != "com.android.systemui" && processName != LAUNCHER_PACKAGE)
        if (skipGoogleProcess) return
        RuntimeSettingsStore.configure(processName, isSystemServer)
        try {
            preferenceRetryGeneration.incrementAndGet()
            EzXposed.initOnModuleLoaded(this, param)
            NativeRules.bindProcess(processName)
            ProcessLogging.bind()
            DebugLog.setProcessTag(processName)
            DebugLog.bindXposed(this)
            initPreferences()
            DebugLog.ensureSession()
            DebugLog.d("HookEntry", "module loaded (Lite) process=$processName")
            publishNativeRuleSwitches()
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "module load handling failed", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // 精簡版無需注入 system_server
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (skipGoogleProcess) return
        if (param.packageName != "com.android.systemui" && param.packageName != LAUNCHER_PACKAGE) return
        if (!injectedPackages.add(param.packageName)) return
        try {
            EzXposed.initOnPackageLoaded(param)
            EzReflect.init(param.defaultClassLoader)
            recordPackageState(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage,
                isPackageReady = false,
                appContext = null
            )
            DebugLog.d(
                "HookEntry",
                "package loaded package=${param.packageName} process=$processName first=${param.isFirstPackage}"
            )

            dispatchPackageHookers(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage
            )
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package load handling failed package=${param.packageName}", t)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (skipGoogleProcess) return
        if (param.packageName != "com.android.systemui" && param.packageName != LAUNCHER_PACKAGE) return
        try {
            EzXposed.initOnPackageReady(param)
            recordPackageState(
                packageName = param.packageName,
                classLoader = param.classLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = runCatching { EzXposed.appContextOrNull }.getOrNull(),
                pluginStates = currentPluginStates(param.packageName)
            )

            handlePackageReadyContext(param.packageName, param.classLoader)
            if (processName == LAUNCHER_PACKAGE) {
                if (runtimePreferencesSource == null) tryInitPreferences()
                publishNativeRuleSwitches()
            }

            if (param.packageName == "com.android.systemui") {
                val appContext = packageStates[param.packageName]?.appContext
                    ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
                ProcessLifecycleHooks.onSystemUiPackageReady(appContext, param.classLoader)
            }
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package ready handling failed package=${param.packageName}", t)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        if (skipGoogleProcess) return true
        if (com.takekazex.hypertweak.hook.base.dex.DexRuntime.isResolving) return false
        iconSettingsLock.lock()
        val ready = try { com.takekazex.hypertweak.hook.base.HotReloadRetirement.run(
            preflight = {
                if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) cancelSettingsReload()
                ProcessLifecycleHooks.checkHotReloadReady()
                refreshHotReloadSnapshots()
                check(DexKitManager.prepareForHotReload()) { "DexKit native bridge users are still active" }
                val roots = rootHookers.toList()
                val state = HotReloadState.save(
                    processName = processName,
                    isSystemServer = isSystemServer,
                    systemServerClassLoader = systemServerClassLoader,
                    packages = packageStates.values,
                    hookerStates = roots.associate { it.hookerName to it.saveHotReloadState() } +
                        mapOf("HyperTweak.NativeSettings" to nativeRuleStatePublisher?.saveSnapshot(),
                            "HyperTweak.RuntimeSettings" to RuntimeSettingsStore.save())
                )
                param.setSavedInstanceState(state)
                buildList<() -> Unit> {
                    add { generationRetired = true; remoteSettingsReady = false; preferenceRetryGeneration.incrementAndGet() }
                    if (processName == "com.android.systemui") add {
                        ProcessLifecycleHooks.cancelSystemUiRecovery()
                    }
                    roots.forEach { root -> add { root.prepareForHotReload() } }
                    roots.forEach { root -> add { root.resetAfterHotReloadPrepared() } }
                    add { rootHookers.clear() }
                    add { ProcessLifecycleHooks.retireRestartReceiver() }
                    if (processName.substringBefore(':') == "com.android.systemui") add {
                        com.takekazex.hypertweak.hook.bootstrap.SystemUiIconPorts.retire(this@HookEntry)
                    }
                    add { detachRuntimePreferencesListener() }
                    if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty())
                        add { runtimeSettingsReceiver.close() }
                    add { RuntimeSettingsPreferences.clearObservers() }
                    add { runtimeContextRetry.cancel() }
                    add { try { nativeRuleStatePublisher?.close() } finally { nativeRuleStatePublisher = null } }
                    add { DebugLog.prepareForHotReload() }
                }
            },
            rejected = { error ->
                DexKitManager.cancelHotReloadPreparation()
                DebugLog.e("HookEntry", "hot reload preflight failed; old hooks untouched", error)
                if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) resumeSettingsReload()
            },
            cleanupFailed = { error ->
                DebugLog.e("HookEntry", "hot reload cleanup incomplete; accepting replacement for recovery", error)
            }
        ) } finally { iconSettingsLock.unlock() }
        return ready
    }

    private fun refreshHotReloadSnapshots() {
        packageStates["com.android.systemui"]?.let { state ->
            packageStates["com.android.systemui"] = state.copy(
                pluginStates = ProcessLifecycleHooks.snapshotSystemUiPlugins()
            )
        }
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        skipGoogleProcess = isSystemServer || (processName != "com.android.systemui" && processName != LAUNCHER_PACKAGE)
        if (skipGoogleProcess) {
            if (param.oldHookHandles.isNotEmpty()) {
                ProcessLogging.bind()
                DebugLog.setProcessTag(processName)
                DebugLog.bindXposed(this)
                unhookRemainingOldHandles(HotReloadHandleStore(param.oldHookHandles))
                DebugLog.prepareForHotReload()
            }
            return
        }
        RuntimeSettingsStore.configure(processName, isSystemServer)
        generationRetired = false
        if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) cancelSettingsReload()
        preferenceRetryGeneration.incrementAndGet()
        EzXposed.initOnModuleLoaded(this, param)
        NativeRules.bindProcess(processName)
        ProcessLogging.bind()
        DebugLog.setProcessTag(processName)
        DebugLog.bindXposed(this)
        val restoredState = HotReloadState.restore(param.savedInstanceState) ?: recoverAppHostState()
        nativePublisher()?.restoreSnapshot(restoredState?.hookerStates?.get("HyperTweak.NativeSettings"))
        RuntimeSettingsStore.restore(restoredState?.hookerStates?.get("HyperTweak.RuntimeSettings")
            ?: (restoredState?.hookerStates?.get("HyperTweak.NativeSettings") as? Array<*>)?.getOrNull(2))
        initPreferences()
        publishNativeRuleSwitches()
        DebugLog.ensureSession()
        val oldHandles = HotReloadHandleStore(param.oldHookHandles)
        val oldHandleIds = oldHandles.ids

        injectedPackages.clear()
        rootHookers.clear()
        packageStates.clear()
        pendingAppContextPackages.clear()

        if (restoredState == null) {
            unhookRemainingOldHandles(oldHandles)
            return
        }

        processName = restoredState.processName
        isSystemServer = restoredState.isSystemServer
        systemServerClassLoader = restoredState.systemServerClassLoader

        restoredState.packages.forEach { state ->
            recordPackageState(
                packageName = state.packageName,
                classLoader = state.classLoader,
                appInfo = state.appInfo,
                isFirstPackage = state.isFirstPackage,
                isPackageReady = state.isPackageReady,
                appContext = state.appContext,
                pluginStates = state.pluginStates
            )
            injectedPackages.add(state.packageName)
            if (state.appContext != null) {
                pendingAppContextPackages.remove(state.packageName)
            }
            EzReflect.init(state.classLoader)
            dispatchPackageHookers(
                packageName = state.packageName,
                classLoader = state.classLoader,
                appInfo = state.appInfo,
                isFirstPackage = false,
                isPackageReady = state.isPackageReady,
                replacementHandles = oldHandles
            )
            if (state.isPackageReady) {
                onRestoredPackageReady(state, oldHandles)
            }
        }

        restoreHookerStates(restoredState.hookerStates)
        logHotReloadHandleDiff(oldHandleIds, oldHandles)
        unhookRemainingOldHandles(oldHandles)
        retryHookersAfterHotReload()
        publishNativeRuleSwitches()
        if (RuntimeSettingsGroups.forProcess(processName, isSystemServer).isNotEmpty()) resumeSettingsReload()
    }

    private fun recoverAppHostState(): HotReloadTargetState? {
        val owner = AppHostRecovery.owner(processName, isSystemServer) ?: return null
        return runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.app.Application
                ?: return@runCatching null
            val info = app.applicationInfo
            if (app.packageName != owner || info.packageName != owner ||
                info.uid != android.os.Process.myUid() || info.sourceDir.isNullOrBlank()
            ) return@runCatching null
            HotReloadTargetState(
                processName = processName,
                isSystemServer = false,
                systemServerClassLoader = null,
                packages = listOf(HotReloadPackageState(
                    packageName = owner,
                    processName = processName,
                    classLoader = app.classLoader,
                    appInfo = info,
                    isFirstPackage = true,
                    isPackageReady = true,
                    appContext = app,
                )),
            )
        }.getOrNull()
    }

    private fun retryHookersAfterHotReload() {
        rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    private fun logHotReloadHandleDiff(
        oldHandleIds: Set<String>,
        oldHandles: HotReloadHandleStore
    ) {
        val newHandleIds = rootHookers.flatMap { it.collectManagedHookHandles() }
            .mapNotNull { it.id }
            .toSet()
        val replacedCount = oldHandles.totalCount - oldHandles.remainingCount
        DebugLog.d(
            "HookEntry",
            "hot reload registered new handles=${newHandleIds.size} replaced=$replacedCount matched=${newHandleIds.intersect(oldHandleIds).size}"
        )
    }

    private fun unhookRemainingOldHandles(handles: HotReloadHandleStore) {
        handles.remainingHandles().forEach { handle ->
            runCatching {
                handle.unhook()
                handles.markHandled(handle)
            }
        }
    }

    private fun restoreHookerStates(states: Map<String, Any?>) {
        com.takekazex.hypertweak.hook.base.HotReloadHostRecovery.run(
            rootHookers.toList(), states, { it.hookerName },
            { hooker, state -> hooker.restoreHotReloadState(state) },
            { it.recoverGenerationHosts() },
            { hooker, error -> DebugLog.e("HookEntry", "failed to restore/recover ${hooker.hookerName}", error) }
        )
    }

    private fun recordPackageState(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean,
        appContext: Context?,
        pluginStates: List<HotReloadPluginState> = emptyList()
    ) {
        val old = packageStates[packageName]
        packageStates[packageName] = HotReloadPackageState(
            packageName = packageName,
            processName = processName,
            classLoader = classLoader,
            appInfo = appInfo ?: old?.appInfo,
            isFirstPackage = old?.isFirstPackage ?: isFirstPackage,
            isPackageReady = old?.isPackageReady == true || isPackageReady,
            appContext = appContext ?: old?.appContext,
            pluginStates = if (pluginStates.isNotEmpty()) pluginStates else old?.pluginStates.orEmpty()
        )
        if (packageStates[packageName]?.appContext != null) {
            pendingAppContextPackages.remove(packageName)
        }
    }

    private fun handlePackageReadyContext(packageName: String, classLoader: ClassLoader) {
        val appContext = packageStates[packageName]?.appContext ?: runCatching {
            EzXposed.appContextOrNull
        }.getOrNull()?.also { context ->
            recordPackageState(
                packageName = packageName,
                classLoader = classLoader,
                appInfo = packageStates[packageName]?.appInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = context,
                pluginStates = currentPluginStates(packageName)
            )
        }

        if (appContext != null) {
            onPackageReadyContextAvailable(packageName, appContext)
            return
        }

        if (!pendingAppContextPackages.add(packageName)) return

        EzXposed.runOnApplicationAttach(object : ApplicationAttachCallback {
            override fun onApplicationAttached(context: Context) {
                val appContext = context.applicationContext ?: context
                if (appContext.packageName != packageName) return
                if (!pendingAppContextPackages.remove(packageName)) return

                recordPackageState(
                    packageName = packageName,
                    classLoader = classLoader,
                    appInfo = packageStates[packageName]?.appInfo,
                    isFirstPackage = false,
                    isPackageReady = true,
                    appContext = appContext,
                    pluginStates = currentPluginStates(packageName)
                )
                onPackageReadyContextAvailable(packageName, appContext)
            }
        })
    }

    private fun onPackageReadyContextAvailable(packageName: String, appContext: Context) {
        Preferences.initLocalCache(appContext)
        ProcessLifecycleHooks.registerRestartReceiver(appContext)
        attachRuntimeSettingsContext(appContext)
        ProcessLifecycleHooks.onPackageContextReady(processName, packageName, appContext)
        if (packageName == "com.android.systemui") {
            onSystemUiContextReady(appContext)
        }
    }

    private fun onSystemUiContextReady(context: Context) {
        ProcessLifecycleHooks.beforeSystemUiContextAttached()
        nativePublisher()?.attach(context)
        attachRuntimeSettingsContext(context)
        ProcessLifecycleHooks.onSystemUiContextReady(context)
    }

    private fun onRestoredPackageReady(
        state: HotReloadPackageState,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val appContext = state.appContext ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            ProcessLifecycleHooks.registerRestartReceiver(appContext)
            attachRuntimeSettingsContext(appContext)
            ProcessLifecycleHooks.onPackageContextReady(processName, state.packageName, appContext)
            if (state.packageName == "com.android.systemui") {
                onSystemUiContextReady(appContext)
            }
        }
        if (state.packageName == "com.android.systemui") {
            ProcessLifecycleHooks.restoreSystemUiPackage(appContext, state.classLoader, state.pluginStates, replacementHandles)
        }
    }

    private fun currentPluginStates(packageName: String): List<HotReloadPluginState> {
        return if (packageName == "com.android.systemui") {
            ProcessLifecycleHooks.snapshotSystemUiPlugins()
        } else {
            emptyList()
        }
    }

    private fun initPreferences() {
        if (tryInitPreferences()) return
        val retryGeneration = preferenceRetryGeneration.get()
        Thread({
            var attempt = 0
            while (attempt < MAX_PREFS_INIT_RETRIES) {
                runCatching { Thread.sleep(PREFS_RETRY_DELAY_MS * (1L shl attempt)) }
                if (preferenceRetryGeneration.get() != retryGeneration) return@Thread
                if (tryInitPreferences()) {
                    retryPreferenceGatedHookers(retryGeneration)
                    publishNativeRuleSwitches()
                    break
                }
                attempt++
            }
        }, "HyperTweak-PrefsRetry").apply { isDaemon = true }.start()
    }

    private fun retryPreferenceGatedHookers(retryGeneration: Long) {
        if (preferenceRetryGeneration.get() != retryGeneration) return
        rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    private fun publishNativeRuleSwitches() {
        if (generationRetired) return
        if (processName == "com.android.systemui") {
            val context = packageStates[processName]?.appContext
                ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
            val publisher = nativePublisher() ?: return
            if (context != null) { publisher.attach(context); runtimeSettingsReceiver.attach(context) }
            publisher.publish()
            return
        }
        if ((!remoteSettingsReady && !RuntimeSettingsStore.owns(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION)) || processName != LAUNCHER_PACKAGE) return
        runCatching {
            NativeRules.applyRuleSwitches(
                hideRecentsClearButton = Preferences.hideRecentsClearButton(),
                openedFolderColumns = Preferences.openedFolderColumns(),
                contextualSearchLongPress = Preferences.contextualSearchLongPress(),
                assistantWidgets = Preferences.allowAndroidWidgetsToAssistant()
            )
        }.onFailure { t ->
            DebugLog.w("HookEntry", "native rule switch publish failed", t)
        }
    }

    private fun tryInitPreferences(): Boolean {
        return try {
            val remotePrefs = getRemotePreferences(AppSettingsKeys.NAME)
            Preferences.init(remotePrefs)
            RuntimeSettingsGroups.registerImeTargets(Preferences.getStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES))
            bindRuntimePreferencesListener(remotePrefs)
            requestSettingsReload(RuntimeSettingsGroups.forProcess(processName, isSystemServer))
            remoteSettingsReady = true
            true
        } catch (t: Throwable) {
            remoteSettingsReady = false
            Preferences.noteRemoteBackendUnavailable()
            false
        }
    }

    private fun dispatchSystemServerHookers(
        classLoader: ClassLoader,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        // 不注入 system_server
    }

    private fun dispatchPackageHookers(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean = false,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = isFirstPackage,
            isPackageReady = isPackageReady,
            appInfo = appInfo,
            appContext = null
        )

        com.takekazex.hypertweak.hook.base.dex.DexRuntime.registerSource(appInfo)
        val sharedSession = appInfo?.sourceDir?.takeIf { it.isNotBlank() }
            ?.let(com.takekazex.hypertweak.hook.base.dex.DexRuntime::beginSession)
        try {
            if (packageName == "com.android.systemui") com.takekazex.hypertweak.hook.bootstrap.SystemUiIconPorts.bind(this)
            PackageHookPlan.attach(ctx, installIcons = {
                systemUiSettings.iconHookers().forEach { attachHooker(it, classLoader, ctx, replacementHandles) }
                systemUiSettings.recordInstalledIcons()
            }) { hooker ->
                attachHooker(hooker, classLoader, ctx, replacementHandles)
            }
        } finally {
            sharedSession?.close()
        }
    }

    private fun refreshImeSettings(): Boolean = true

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        ctx: ModuleContext,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        if (!shouldAttachHooker(hooker, ctx)) return
        try {
            DebugLog.d("HookEntry", "attaching ${hooker::class.java.simpleName} package=${ctx.packageName}")
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx
            hooker.setHotReloadReplacementHandles(replacementHandles)

            rootHookers.add(hooker)
            hooker.performInit()
            hooker.updateParentState(true)
            hooker.setHotReloadReplacementHandles(null)
        } catch (t: Throwable) {
            hooker.setHotReloadReplacementHandles(null)
            DebugLog.e("HookEntry", "failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
