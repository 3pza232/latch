package dev.latch.rule

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import dev.latch.rule.core.log.EventLog
import dev.latch.rule.data.BackupStore
import dev.latch.rule.data.FileBackupStore
import dev.latch.rule.data.FileEventLog
import dev.latch.rule.data.FileResultStore
import dev.latch.rule.data.FileRuleStore
import dev.latch.rule.data.FileStatsStore
import dev.latch.rule.data.InstalledApps
import dev.latch.rule.data.LatchSettings
import dev.latch.rule.data.PreferencesSettingsStore
import dev.latch.rule.data.ResultStore
import dev.latch.rule.data.RuleStore
import dev.latch.rule.data.SettingsStore
import dev.latch.rule.data.StatsStore
import dev.latch.rule.domain.BackupService
import dev.latch.rule.domain.RestoreService
import dev.latch.rule.domain.RuleCoordinator
import dev.latch.rule.domain.StatsService
import dev.latch.rule.engine.RuleEngine
import dev.latch.rule.engine.VerifyService
import dev.latch.rule.gateway.AgentGateway
import dev.latch.rule.notify.DriftNotifier
import dev.latch.rule.privilege.PrivilegeBroker
import dev.latch.rule.privilege.ShizukuPrivilegeBroker
import dev.latch.rule.work.VerifyScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val fileEventLog: FileEventLog = FileEventLog(File(appContext.filesDir, LOG_DIRECTORY))
    val eventLog: EventLog = fileEventLog
    val privilegeBroker: PrivilegeBroker = ShizukuPrivilegeBroker()
    val ruleEngine: RuleEngine = RuleEngine(privilegeBroker)
    val ruleStore: RuleStore = FileRuleStore(appContext)
    val resultStore: ResultStore = FileResultStore(appContext)
    val settingsStore: SettingsStore = PreferencesSettingsStore(appContext)
    val backupStore: BackupStore = FileBackupStore(File(appContext.filesDir, BACKUP_DIRECTORY))
    val backupService: BackupService = BackupService(backupStore, ruleStore, eventLog = eventLog)
    val statsStore: StatsStore = FileStatsStore(File(appContext.filesDir, STATS_DIRECTORY))
    val statsService: StatsService = StatsService(fileEventLog, statsStore)
    val verifyService: VerifyService = VerifyService(ruleStore, resultStore, ruleEngine, eventLog)
    val ruleCoordinator: RuleCoordinator = RuleCoordinator(
        ruleStore,
        resultStore,
        ruleEngine,
        verifyService,
        eventLog,
        backupService,
    ) { settingsStore.load().autoReapplyOnUnsuppress }
    val restoreService: RestoreService = RestoreService(backupService, ruleCoordinator, eventLog)
    val agentGateway: AgentGateway = AgentGateway(
        appContext,
        ruleCoordinator,
        privilegeBroker,
        eventLog,
        backupService,
        restoreService,
        statsService,
    )
    val driftNotifier: DriftNotifier = DriftNotifier(appContext)
    val installedApps: InstalledApps = InstalledApps(appContext)
    val contentResolver: ContentResolver get() = appContext.contentResolver

    init {
        appScope.launch {
            // Fold the log into the persisted counters before any pruning.
            statsService.refresh()
            backupService.snapshotOnStartup()
            backupService.prune()
            fileEventLog.prune(settingsStore.load().logRetentionDays)
        }
    }

    /** Applies settings and keeps background scheduling in sync. */
    fun updateSettings(settings: LatchSettings) {
        settingsStore.save(settings)
        VerifyScheduler.applyPeriodic(appContext, settings.periodicMinutes)
        pruneLogs(settings.logRetentionDays)
    }

    fun pruneLogs(keepDays: Int) {
        appScope.launch {
            statsService.refresh()
            fileEventLog.prune(keepDays)
        }
    }

    private companion object {
        const val LOG_DIRECTORY = "logs"
        const val BACKUP_DIRECTORY = "backups"
        const val STATS_DIRECTORY = "stats"
    }
}
