/*
 * DroidVNC-NG configurable keyboard shortcuts.
 *
 * Author is slab-tsuchiya <https://github.com/slab-tsuchiya>.
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 */

package net.christianbeier.droidvnc_ng

import net.christianbeier.droidvnc_ng.InputKeyShortcut.Action
import net.christianbeier.droidvnc_ng.InputKeyShortcut.Chord
import net.christianbeier.droidvnc_ng.InputKeyShortcut.Manager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local unit tests for the [InputKeyShortcut] model: chord (de)serialization and the manager's
 * lookup and duplicate detection. They stay off Android APIs -- only [Chord.fromString] (via
 * [InputKeysyms]) and plain data structures are exercised.
 */
class InputKeyShortcutTest {

    /** Modifiers match by exact case (like the trigger) and canonicalize their right variant to left. */
    @Test
    fun chordParsingAndCanonicalForm() {
        // canonical round-trip
        assertEquals("Control_L+Alt_L+Delete", Chord.fromString("Control_L+Alt_L+Delete").toString())
        // modifier order does not matter and a right-hand modifier canonicalizes to its left name
        assertEquals("Control_L+Shift_L+Escape", Chord.fromString("Shift_L+Control_R+Escape").toString())
        // both modifiers and the trigger are matched by exact case, so mis-cased tokens are dropped
        assertEquals("Home", Chord.fromString("Home").toString())
        assertEquals("", Chord.fromString("home").toString())
        assertEquals("Escape", Chord.fromString("control_l+Escape").toString())
    }

    @Test
    fun unassignedChords() {
        for (s in listOf(null, "", "+", "Control_L", "NotAKey", "Control_L+NotAKey")) {
            assertTrue("expected unassigned for <$s>", !Chord.fromString(s).isAssigned)
        }
    }

    /** The manager resolves an exact (modifier state, keysym) to its action and nothing else. */
    @Test
    fun managerResolvesExactChords() {
        val chords = mapOf(
            Action.HOME to "Home",                          // 0xFF50, no modifiers
            Action.RECENTS to "Control_L+Shift_L+Escape",   // 0xFF1B, ctrl+shift
        )
        val m = Manager.from { chords[it] }

        assertEquals(Action.HOME, m.actionFor(false, false, false, 0xFF50L))
        assertEquals(Action.RECENTS, m.actionFor(true, false, true, 0xFF1BL))
        // bare Escape is not bound, and Home with a modifier held is a different chord
        assertNull(m.actionFor(false, false, false, 0xFF1BL))
        assertNull(m.actionFor(true, false, false, 0xFF50L))
        assertTrue(m.conflicts.isEmpty())
    }

    /** Two actions on the same chord: it is reported as a conflict and the earlier action wins. */
    @Test
    fun managerFlagsDuplicateChords() {
        val chords = mapOf(
            Action.HOME to "Home",  // declared before BACK
            Action.BACK to "Home",
        )
        val m = Manager.from { chords[it] }

        assertTrue(Chord.fromString("Home") in m.conflicts)
        assertEquals(Action.HOME, m.actionFor(false, false, false, 0xFF50L))
    }

    @Test
    fun actionsHaveDistinctPrefKeys() {
        val keys = Action.entries.map { it.prefKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
