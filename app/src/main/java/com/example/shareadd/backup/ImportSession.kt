package com.example.shareadd.backup

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ImportPhase {
    /** normal operation: the screens may save */
    IDLE,
    /** a confirmed import is being written: nothing else may write the stores */
    APPLYING,
    /** an import journal exists but could not be written: saving stays blocked until it is applied */
    RECOVERY_REQUIRED
}

sealed class ImportNotice {
    /** the confirmed plan was written completely */
    data class Applied(val plan: ImportPlan) : ImportNotice()
    /** the import failed before anything was written; no data changed */
    object FailedNoChange : ImportNotice()
    /** an interrupted import (journal) was completed */
    object Recovered : ImportNotice()
}

/** Result of one apply, as seen by [ImportLifecycle]. */
sealed class ApplyOutcome {
    object Success : ApplyOutcome()
    /** [journalRemains] = the new state was journaled but not written: it must be completed before saving again */
    data class Failed(val journalRemains: Boolean) : ApplyOutcome()
}

data class ImportState(val phase: ImportPhase, val generation: Int)

/**
 * F-01 / F-02: who may write the stores, independent of any screen.
 *  - Saving is allowed only in [ImportPhase.IDLE].
 *  - Only one apply at a time.
 *  - [generation] grows every time the stores were rewritten by an import or a recovery; a screen built for an
 *    older generation holds stale data and must be rebuilt from storage.
 * Pure Kotlin (no Android types) so it can be tested on the JVM.
 */
class ImportLifecycle {
    private var phase = ImportPhase.IDLE
    private var generation = 0
    private var notice: ImportNotice? = null

    @Synchronized fun state() = ImportState(phase, generation)

    @Synchronized fun canAutoSave() = phase == ImportPhase.IDLE

    /** Starts an apply; false when one is running or a recovery is pending. */
    @Synchronized fun tryBeginApply(): Boolean {
        if (phase != ImportPhase.IDLE) return false
        phase = ImportPhase.APPLYING
        notice = null
        return true
    }

    @Synchronized fun finishApply(outcome: ApplyOutcome, plan: ImportPlan?) {
        check(phase == ImportPhase.APPLYING) { "no apply running" }
        when (outcome) {
            ApplyOutcome.Success -> {
                phase = ImportPhase.IDLE
                generation++
                notice = plan?.let { ImportNotice.Applied(it) }
            }
            is ApplyOutcome.Failed -> {
                if (outcome.journalRemains) {
                    phase = ImportPhase.RECOVERY_REQUIRED
                    notice = null
                } else {
                    phase = ImportPhase.IDLE
                    notice = ImportNotice.FailedNoChange
                }
            }
        }
    }

    /**
     * Startup / retry: completes a pending journal. Never runs while an apply is in progress (the apply owns the
     * journal). Returns true when no journal is left.
     */
    @Synchronized fun recover(journalExists: () -> Boolean, applyJournal: () -> Boolean): Boolean {
        if (phase == ImportPhase.APPLYING) return false
        if (!journalExists()) {
            if (phase == ImportPhase.RECOVERY_REQUIRED) {
                phase = ImportPhase.IDLE
                generation++
            }
            return true
        }
        return if (applyJournal()) {
            phase = ImportPhase.IDLE
            generation++
            notice = ImportNotice.Recovered
            true
        } else {
            phase = ImportPhase.RECOVERY_REQUIRED
            false
        }
    }

    /**
     * Activity creation: runs the startup recovery only when nothing is pending in this process. While an apply is
     * running or a recovery is required, a recreated activity (rotation) changes nothing — the protection stays until
     * the apply finishes or the user retries (a new process starts IDLE and recovers normally).
     */
    @Synchronized fun recoverOnActivityCreate(journalExists: () -> Boolean, applyJournal: () -> Boolean): Boolean {
        if (phase != ImportPhase.IDLE) return false
        return recover(journalExists, applyJournal)
    }

    @Synchronized fun consumeNotice(): ImportNotice? = notice.also { notice = null }
}

/**
 * The process-wide import coordinator. Its work runs in its own scope, so a confirmed import is not cancelled when
 * the activity is recreated (rotation, theme/locale change) or the screen leaves composition.
 */
object ImportSession {
    private val lifecycle = ImportLifecycle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(lifecycle.state())
    val state: StateFlow<ImportState> = _state

    fun canAutoSave(): Boolean = lifecycle.canAutoSave()

    fun consumeNotice(): ImportNotice? = lifecycle.consumeNotice()

    private fun publish() {
        _state.value = lifecycle.state()
    }

    /**
     * Called from Activity.onCreate before any store is read. Completes a pending journal (F-02: a failure keeps the
     * journal and blocks saving) and removes staging folders of unconfirmed previews — but does nothing while an
     * import is being applied (that import owns its journal and staging files) or while a recovery is required
     * (the protection stays across rotation until the user retries).
     */
    fun onActivityCreate(context: Context) {
        val app = context.applicationContext
        lifecycle.recoverOnActivityCreate({ BackupFiles.journal(app).exists() }, { applyImportJournal(app) })
        publish()
        if (lifecycle.state().phase == ImportPhase.IDLE) BackupFiles.cleanupStaleStaging(app)
    }

    /** Applies a confirmed plan; false (nothing started) when another apply or a recovery is pending. */
    fun startApply(context: Context, plan: ImportPlan, staged: StagedBackup): Boolean {
        val app = context.applicationContext
        if (!lifecycle.tryBeginApply()) return false
        publish()
        scope.launch {
            val outcome = try {
                applyImport(app, plan, staged)
                ApplyOutcome.Success
            } catch (_: Exception) {
                ApplyOutcome.Failed(journalRemains = BackupFiles.journal(app).exists())
            }
            lifecycle.finishApply(outcome, plan)
            publish()
        }
        return true
    }

    /** Retry from the recovery screen. */
    fun retryRecovery(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val ok = lifecycle.recover({ BackupFiles.journal(app).exists() }, { applyImportJournal(app) })
            publish()
            onDone(ok)
        }
    }
}
