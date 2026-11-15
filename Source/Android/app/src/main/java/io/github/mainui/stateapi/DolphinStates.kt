// SPDX-License-Identifier: GPL-2.0-or-later

package io.github.mainui.stateapi

import org.dolphinemu.dolphinemu.NativeLibrary
import java.io.File

/**
 * Dolphin's side of the MainUI State API (see MainUIStateApi): slots 0-9 are Dolphin's own state
 * slots (9 is its quick save), the resume slot a file of its own in StateSaves. Dolphin saves on
 * its CPU thread and compresses in the background, so a save is only reported once the file is
 * complete (Dolphin's slot lookup waits for saves in progress).
 */
object DolphinStates : MainUIStateApi.Emulator {
    /** When the game running now was started (its emulation screen created), ms since 1970. */
    @Volatile private var startedAt = Long.MAX_VALUE

    /** A game's emulation screen is up: MainUI's commands go to it from now on. */
    fun attach() {
        startedAt = System.currentTimeMillis()
        MainUIStateApi.attach(this)
    }

    override fun isRunning(): Boolean = NativeLibrary.IsRunning() && NativeLibrary.IsGameMetadataValid()

    override fun isReady(): Boolean = NativeLibrary.IsRunningAndUnpaused()

    override fun slotCount(): Int = 10

    /** MainUI's pause took effect (not one the user or Dolphin's menu holds). */
    @Volatile private var pausedByMainUi = false

    override fun pause(): Boolean {
        if (!NativeLibrary.IsRunningAndUnpaused()) return true
        NativeLibrary.PauseEmulation(false)
        // (Dolphin may refuse: RetroAchievements hardcore's limit on pausing.)
        if (NativeLibrary.IsRunningAndUnpaused()) return false
        pausedByMainUi = true
        return true
    }

    override fun resume() {
        if (!pausedByMainUi) return
        pausedByMainUi = false
        NativeLibrary.UnPauseEmulation()
    }

    override fun liftOwnPause(): Boolean {
        if (pausedByMainUi || !isRunning() || NativeLibrary.IsRunningAndUnpaused()) return false
        NativeLibrary.UnPauseEmulation()
        return true
    }

    override fun restoreOwnPause() {
        if (NativeLibrary.IsRunningAndUnpaused()) NativeLibrary.PauseEmulation(false)
        pausedByMainUi = false // (the user's pause again, even if MainUI paused meanwhile)
    }

    private fun stateDir() = File(NativeLibrary.GetUserDirectory(), "StateSaves")

    private fun resumeFile() = File(stateDir(), "${NativeLibrary.GetCurrentGameID()}.mainui.sav")

    private fun slotFile(slot: Int) = File(stateDir(), "%s.s%02d".format(NativeLibrary.GetCurrentGameID(), slot))

    override fun save(slot: Int): String {
        if (slot == MainUIStateApi.RESUME_SLOT) {
            val f = resumeFile()
            val before = if (f.isFile) f.lastModified() else 0L
            NativeLibrary.SaveStateAs(f.path)
            if (!MainUIStateApi.await({ f.isFile && f.lastModified() != before }, 30_000)) throw IllegalStateException("Dolphin didn't write the state")
            NativeLibrary.GetUnixTimeOfStateSlot(0) // returns once no save is in progress any more
            return f.path
        }
        val before = NativeLibrary.GetUnixTimeOfStateSlot(slot)
        NativeLibrary.SaveState(slot)
        if (!MainUIStateApi.await({ NativeLibrary.GetUnixTimeOfStateSlot(slot) != before }, 30_000)) throw IllegalStateException("Dolphin didn't write the state")
        return slotFile(slot).path
    }

    override fun load(slot: Int) {
        if (slot == MainUIStateApi.RESUME_SLOT) {
            val f = resumeFile()
            // The game saved after this state was made, in a session that ended without MainUI's
            // save: the state holds what the game knew of its saves back then (a GameCube state the
            // memory card itself), and loading it would put that over the newer saves.
            if (f.isFile && lastSaveBeforeThisRun() > f.lastModified()) throw IllegalStateException("the game has newer saves of its own")
            NativeLibrary.LoadStateAs(f.path)
        } else NativeLibrary.LoadState(slot)
    }

    /**
     * When this game last wrote its own saves (Wii save data on the NAND, GameCube memory card
     * files) before this run of it, ms since 1970; writes while it boots now don't count.
     */
    private fun lastSaveBeforeThisRun(): Long {
        val code = NativeLibrary.GetCurrentGameID().take(4)
        if (code.length < 4) return 0L
        val user = File(NativeLibrary.GetUserDirectory())
        val hex = code.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        val wii = listOf("00010000", "00010001").asSequence()
            .flatMap { File(user, "Wii/title/$it/$hex/data").walkTopDown() }
        val gc = File(user, "GC").walkTopDown().filter { it.name.endsWith(".gci") && it.name.contains("-$code-") }
        return (wii + gc).filter { it.isFile }.map { it.lastModified() }.filter { it < startedAt }.maxOrNull() ?: 0L
    }

    override fun slotTime(slot: Int): Long =
        if (slot == MainUIStateApi.RESUME_SLOT) resumeFile().let { if (it.isFile) it.lastModified() else 0L }
        else NativeLibrary.GetUnixTimeOfStateSlot(slot)
}
