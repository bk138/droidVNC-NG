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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local unit tests for [InputKeysyms] over the shipped [InputKeysymTable]. They pin the two things
 * that a table regeneration or a lookup refactor could silently change: the longest-name-wins alias
 * rule and case-sensitive matching.
 */
class InputKeysymsTest {

    /** 0xFF55 is Prior/Page_Up, 0xFF56 is Next/Page_Down, 0xFFC8 is L1/F11 -- the longest name wins. */
    @Test
    fun longestAliasWins() {
        assertEquals("Page_Up", InputKeysyms.nameOf(0xFF55L))
        assertEquals("Page_Down", InputKeysyms.nameOf(0xFF56L))
        assertEquals("F11", InputKeysyms.nameOf(0xFFC8L))
    }

    /** Case is significant: XK_A and XK_a are distinct, and only the exact spelling resolves. */
    @Test
    fun lookupIsCaseSensitive() {
        assertEquals(0x0041L, InputKeysyms.keysymFor("A"))
        assertEquals(0x0061L, InputKeysyms.keysymFor("a"))
        assertEquals(0xFF55L, InputKeysyms.keysymFor("Page_Up"))
        assertNull(InputKeysyms.keysymFor("page_up"))
    }

    /** Every curated trigger key round-trips name -> keysym -> canonical name. */
    @Test
    fun canonicalNamesRoundTrip() {
        for (name in listOf("Home", "End", "Escape", "Delete", "Return", "Tab", "F1", "F12")) {
            assertEquals(name, InputKeysyms.nameOf(InputKeysyms.keysymFor(name)!!))
        }
    }

    @Test
    fun unknownInputIsNull() {
        assertNull(InputKeysyms.keysymFor("NotAKey"))
        assertNull(InputKeysyms.keysymFor(""))
        assertNull(InputKeysyms.nameOf(0L))
    }
}
