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
    override fun isRunning(): Boolean = NativeLibrary.IsRunning() && NativeLibrary.IsGameMetadataValid()

    override fun isReady(): Boolean = NativeLibrary.IsRunningAndUnpaused()

    override fun slotCount(): Int = 10

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
        if (slot == MainUIStateApi.RESUME_SLOT) NativeLibrary.LoadStateAs(resumeFile().path)
        else NativeLibrary.LoadState(slot)
    }

    override fun slotTime(slot: Int): Long =
        if (slot == MainUIStateApi.RESUME_SLOT) resumeFile().let { if (it.isFile) it.lastModified() else 0L }
        else NativeLibrary.GetUnixTimeOfStateSlot(slot)
}
