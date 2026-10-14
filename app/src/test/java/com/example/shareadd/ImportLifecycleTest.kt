package com.example.shareadd

import com.example.shareadd.backup.ApplyOutcome
import com.example.shareadd.backup.BackupSettings
import com.example.shareadd.backup.BackupSnapshot
import com.example.shareadd.backup.CurrentData
import com.example.shareadd.backup.ImportLifecycle
import com.example.shareadd.backup.ImportMode
import com.example.shareadd.backup.ImportNotice
import com.example.shareadd.backup.ImportPhase
import com.example.shareadd.backup.planImport
import com.example.shareadd.backup.stageBackup
import com.example.shareadd.backup.writeBackupV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** F-01 / F-02: who may write the stores while an import is applied or a journal is pending. */
class ImportLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun place(id: String, url: String) = Place(
        id = id, placeName = id, title = "t", url = url, description = "", privateNotes = "", categoryAr = "أخرى",
        categoryEn = "Other", images = emptyList(), rating = 0, favorite = false, pinned = false, createdAt = 1, updatedAt = 1
    )

    /** a real plan, so the notice carries the exact plan that was applied */
    private fun plan() = run {
        val out = ByteArrayOutputStream()
        writeBackupV2(out, BackupSnapshot(listOf(place("pl-1", "https://a.test")), emptyList(), BackupSettings(true, true), emptyList(), emptyList(), 1, "1.0", 1), { null })
        val staged = stageBackup(ByteArrayInputStream(out.toByteArray()), tmp.newFolder(), { null }, { false })
        planImport(CurrentData(emptyList(), emptyList(), emptyList(), emptyList()), staged, ImportMode.REPLACE_ALL, tmp.newFolder(), now = 1)
    }

    @Test
    fun autoSaveIsBlockedWhileAnImportIsApplied() {
        val l = ImportLifecycle()
        assertTrue(l.canAutoSave())
        assertTrue(l.tryBeginApply())
        assertEquals(ImportPhase.APPLYING, l.state().phase)
        assertFalse(l.canAutoSave())
    }

    @Test
    fun onlyOneApplyAtATime() {
        val l = ImportLifecycle()
        assertTrue(l.tryBeginApply())
        assertFalse("a second confirm (e.g. after rotation) must not start another apply", l.tryBeginApply())
        l.finishApply(ApplyOutcome.Success, plan())
        assertTrue(l.tryBeginApply())
    }

    @Test
    fun successResumesSavingOnlyWithANewGenerationSoScreensReloadFromStorage() {
        val l = ImportLifecycle()
        val before = l.state().generation
        l.tryBeginApply()
        val p = plan()
        l.finishApply(ApplyOutcome.Success, p)
        assertTrue(l.canAutoSave())
        assertEquals("a completed import invalidates every screen built before it", before + 1, l.state().generation)
        assertEquals(ImportNotice.Applied(p), l.consumeNotice())
        assertNull("the result is shown once", l.consumeNotice())
    }

    @Test
    fun failureWithAJournalLeftBlocksSavingAndReportsNoSuccess() {
        val l = ImportLifecycle()
        l.tryBeginApply()
        val gen = l.state().generation
        l.finishApply(ApplyOutcome.Failed(journalRemains = true), plan())
        assertEquals(ImportPhase.RECOVERY_REQUIRED, l.state().phase)
        assertFalse(l.canAutoSave())
        assertFalse(l.consumeNotice() is ImportNotice.Applied)
        assertEquals(gen, l.state().generation)
        assertFalse("no new import while recovery is pending", l.tryBeginApply())
    }

    @Test
    fun failureBeforeAnythingWasWrittenReturnsToNormalWithAFailureNotice() {
        val l = ImportLifecycle()
        l.tryBeginApply()
        val gen = l.state().generation
        l.finishApply(ApplyOutcome.Failed(journalRemains = false), plan())
        assertTrue(l.canAutoSave())
        assertEquals(ImportNotice.FailedNoChange, l.consumeNotice())
        assertEquals("nothing was rewritten", gen, l.state().generation)
    }

    @Test
    fun startupWithoutJournalStartsNormallyAndNeverTouchesStores() {
        val l = ImportLifecycle()
        var applied = false
        assertTrue(l.recover({ false }, { applied = true; true }))
        assertFalse(applied)
        assertTrue(l.canAutoSave())
        assertEquals(0, l.state().generation)
        assertNull(l.consumeNotice())
    }

    @Test
    fun startupJournalSuccessClearsRecoveryAndReloads() {
        val l = ImportLifecycle()
        assertTrue(l.recover({ true }, { true }))
        assertTrue(l.canAutoSave())
        assertEquals(1, l.state().generation)
        assertEquals(ImportNotice.Recovered, l.consumeNotice())
    }

    @Test
    fun startupJournalFailureKeepsRecoveryStateUntilARetrySucceeds() {
        val l = ImportLifecycle()
        var journal = true
        var writable = false
        val applyJournal = { if (writable) { journal = false; true } else false }

        assertFalse(l.recover({ journal }, applyJournal))
        assertEquals(ImportPhase.RECOVERY_REQUIRED, l.state().phase)
        assertFalse(l.canAutoSave())
        assertTrue("the journal is kept for the retry", journal)
        assertFalse(l.consumeNotice() is ImportNotice.Recovered)

        // e.g. the activity is recreated: still blocked
        assertFalse(l.recover({ journal }, applyJournal))
        assertFalse(l.canAutoSave())

        writable = true
        assertTrue(l.recover({ journal }, applyJournal))
        assertTrue(l.canAutoSave())
        assertEquals(1, l.state().generation)
        assertEquals(ImportNotice.Recovered, l.consumeNotice())
    }

    @Test
    fun recreatedActivityKeepsTheRecoveryProtectionUntilTheUserRetries() {
        val l = ImportLifecycle()
        var journal = true
        var writable = false
        val applyJournal = { if (writable) { journal = false; true } else false }
        assertFalse(l.recoverOnActivityCreate({ journal }, applyJournal))       // process start: fails
        assertEquals(ImportPhase.RECOVERY_REQUIRED, l.state().phase)

        writable = true // even if writing would now work, a rotation alone does not apply the journal
        var called = false
        assertFalse(l.recoverOnActivityCreate({ called = true; journal }, { called = true; applyJournal() }))
        assertFalse(called)
        assertTrue(journal)
        assertFalse(l.canAutoSave())

        assertTrue("explicit retry", l.recover({ journal }, applyJournal))
        assertTrue(l.canAutoSave())
    }

    @Test
    fun recoveryNeverRunsWhileAnImportIsBeingApplied() {
        val l = ImportLifecycle()
        l.tryBeginApply()
        var touched = false
        // the activity is recreated during the apply: the running apply owns the journal
        assertFalse(l.recover({ touched = true; true }, { touched = true; true }))
        assertFalse(touched)
        assertEquals(ImportPhase.APPLYING, l.state().phase)
    }
}
