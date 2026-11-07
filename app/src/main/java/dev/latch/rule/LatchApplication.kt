package dev.latch.rule

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import dev.latch.rule.data.FileResultStore
import dev.latch.rule.data.FileRuleStore
import dev.latch.rule.data.InstalledApps
import dev.latch.rule.data.LatchSettings
import dev.latch.rule.data.PreferencesSettingsStore
import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.SettingsStore
import dev.latch.rule.domain.RuleCoordinator
import dev.latch.rule.engine.RuleEngine
import dev.latch.rule.engine.VerifyService
import dev.latch.rule.gateway.AgentGateway
import dev.latch.rule.notify.DriftNotifier
import dev.latch.rule.privilege.PrivilegeBroker
import dev.latch.rule.privilege.ShizukuPrivilegeBroker
import dev.latch.rule.work.VerifyScheduler

/** Process-wide dependency graph. Keep construction explicit and cheap. */
class LatchApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val privilegeBroker: PrivilegeBroker = ShizukuPrivilegeBroker()
    val ruleEngine: RuleEngine = RuleEngine(privilegeBroker)
    val ruleStore: RuleStore = FileRuleStore(appContext)
    val resultStore: ResultStore = FileResultStore(appContext)
    val settingsStore: SettingsStore = PreferencesSettingsStore(appContext)
    val verifyService: VerifyService = VerifyService(ruleStore, resultStore, ruleEngine)
    val ruleCoordinator: RuleCoordinator = RuleCoordinator(ruleStore, resultStore, ruleEngine, verifyService)
    val agentGateway: AgentGateway = AgentGateway(appContext, ruleCoordinator, privilegeBroker)
    val driftNotifier: DriftNotifier = DriftNotifier(appContext)
    val installedApps: InstalledApps = InstalledApps(appContext)
    val contentResolver: ContentResolver get() = appContext.contentResolver

    /** Applies settings and keeps background scheduling in sync. */
    fun updateSettings(settings: LatchSettings) {
        settingsStore.save(settings)
        VerifyScheduler.applyPeriodic(appContext, settings.periodicMinutes)
    }
}
