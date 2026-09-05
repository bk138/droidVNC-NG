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

/**
 * Name<->keysym lookups over the generated [InputKeysymTable].
 *
 * The table mirrors libxkbcommon's ks_tables.h: one case-sensitively sorted name blob plus parallel
 * offset/keysym arrays and a keysym-ordered index, so both directions are a binary search -- no map,
 * and no allocation on the name side. Kept hand-written (and unit-tested) rather than generated, so
 * the searches and the alias-naming rule stay reviewable while the generator only emits data.
 */
internal object InputKeysyms {

    /**
     * The keysym for an exact, case-sensitive XK_ name (prefix stripped), or null if unknown. Case
     * matters: "A" and "a" are distinct keysyms and only the exact spelling resolves.
     */
    fun keysymFor(name: String): Long? {
        val offsets = InputKeysymTable.OFFSETS
        var lo = 0
        var hi = InputKeysymTable.KEYSYMS.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareToName(name, offsets[mid], offsets[mid + 1])
            when {
                cmp < 0 -> hi = mid - 1
                cmp > 0 -> lo = mid + 1
                else -> return InputKeysymTable.KEYSYMS[mid]
            }
        }
        return null
    }

    /**
     * The canonical XK_ name for a keysym, or null if none. A keysym often has several aliases
     * (0xFF55 is both Prior and Page_Up); the longest name wins -- ties broken by header order -- so
     * an alias resolves to the descriptive standard name (Page_Up, Page_Down, F11).
     */
    fun nameOf(keysym: Long): String? {
        val byKeysym = InputKeysymTable.BY_KEYSYM
        val keysyms = InputKeysymTable.KEYSYMS
        // binary search for any entry with this keysym, then widen to the whole run of its aliases
        var lo = 0
        var hi = byKeysym.size - 1
        var hit = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = keysyms[byKeysym[mid]]
            when {
                k < keysym -> lo = mid + 1
                k > keysym -> hi = mid - 1
                else -> { hit = mid; break }
            }
        }
        if (hit < 0) return null
        var start = hit
        while (start > 0 && keysyms[byKeysym[start - 1]] == keysym) start--
        var end = hit
        while (end + 1 < byKeysym.size && keysyms[byKeysym[end + 1]] == keysym) end++
        // longest name wins; the run is in header order, so a strict > keeps the first one on a tie
        val offsets = InputKeysymTable.OFFSETS
        var best = byKeysym[start]
        for (i in start + 1..end) {
            val idx = byKeysym[i]
            if (offsets[idx + 1] - offsets[idx] > offsets[best + 1] - offsets[best]) best = idx
        }
        return InputKeysymTable.NAMES.substring(offsets[best], offsets[best + 1])
    }

    /** Compares [name] to the [NAMES] slice `[start, end)` without allocating (all names are ASCII). */
    private fun compareToName(name: String, start: Int, end: Int): Int {
        val names = InputKeysymTable.NAMES
        val a = name.length
        val b = end - start
        val min = if (a < b) a else b
        for (i in 0 until min) {
            val d = name[i].code - names[start + i].code
            if (d != 0) return d
        }
        return a - b
    }
}
