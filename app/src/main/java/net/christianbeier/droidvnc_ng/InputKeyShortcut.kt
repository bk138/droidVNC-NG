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
 * Configurable VNC keyboard shortcuts (see issue #13), gathered under one type that matches the
 * file name:
 *  - [Action]: the device action a chord is assigned to. Each constant also carries its persisted
 *    prefs key, its UI label and its built-in default chord, so a new action cannot be added without
 *    supplying all three and the callers (InputService, the settings dialog) can iterate the
 *    constants instead of repeating the list.
 *  - [TriggerKey]: the trigger keys we offer pre-curated with localised labels. A chord
 *    may name any X11 key (see [Chord]); this is only the curated subset that gets a picker entry.
 *  - [Chord]: ctrl/alt/shift modifier flags plus the RFB/X11 [Chord.keysym] of one trigger key.
 *    Serializes to/from a "+"-joined string such as "Control_L+Alt_L+Delete" or "Escape".
 *  - [Manager]: resolves an incoming (modifier state, trigger keysym) to its [Action], and reports
 *    any chord assigned to more than one action so the settings UI can reject a duplicate.
 *
 * Nothing here makes an Android runtime call (no accessibility / root / MediaProjection) -- the
 * caller (InputService) executes the returned [Action] -- so the model stays unit-testable. The
 * per-action metadata does reference app resources ([Constants], R.string, [Defaults]), but only the
 * default-chord lookup ever touches a [Defaults] instance, and the tests don't exercise that path.
 */
internal object InputKeyShortcut {

    /**
     * A device action a chord can be bound to. Execution lives in the caller (InputService); this
     * enum only ties each action to its persisted [prefKey], its UI [labelRes] and its built-in
     * default chord (via [defaultChord], which honors defaults.json / managed config).
     */
    enum class Action(
        val prefKey: String,
        val labelRes: Int,
        private val defaultOf: (Defaults) -> String,
    ) {
        RECENTS(Constants.PREFS_KEY_SETTINGS_CHORD_RECENTS, R.string.main_activity_settings_chord_recents, { it.chordRecents }),
        HOME(Constants.PREFS_KEY_SETTINGS_CHORD_HOME, R.string.main_activity_settings_chord_home, { it.chordHome }),
        BACK(Constants.PREFS_KEY_SETTINGS_CHORD_BACK, R.string.main_activity_settings_chord_back, { it.chordBack }),
        POWER_DIALOG(Constants.PREFS_KEY_SETTINGS_CHORD_POWER, R.string.main_activity_settings_chord_power, { it.chordPower }),
        VOLUME_UP(Constants.PREFS_KEY_SETTINGS_CHORD_VOLUME_UP, R.string.main_activity_settings_chord_volume_up, { it.chordVolumeUp }),
        VOLUME_DOWN(Constants.PREFS_KEY_SETTINGS_CHORD_VOLUME_DOWN, R.string.main_activity_settings_chord_volume_down, { it.chordVolumeDown }),
        ROTATE(Constants.PREFS_KEY_SETTINGS_CHORD_ROTATE, R.string.main_activity_settings_chord_rotate, { it.chordRotate });

        /** The built-in default chord string for this action, resolved against [defaults]. */
        fun defaultChord(defaults: Defaults): String = defaultOf(defaults)
    }

    /**
     * The trigger keys the settings UI offers, in picker order, each with the localized label to show
     * for it. Deliberately a curated subset: a chord can name any X11 key (see [Chord.fromString]),
     * so a key that is not listed here can still be bound through defaults.json / managed config --
     * it just has no picker entry and is shown by its XK name.
     */
    enum class TriggerKey(val xkName: String, val labelRes: Int) {
        HOME("Home", R.string.key_label_home),
        END("End", R.string.key_label_end),
        ESCAPE("Escape", R.string.key_label_esc),
        DELETE("Delete", R.string.key_label_del),
        INSERT("Insert", R.string.key_label_ins),
        BACKSPACE("BackSpace", R.string.key_label_backspace),
        PAGE_UP("Page_Up", R.string.key_label_pageup),
        PAGE_DOWN("Page_Down", R.string.key_label_pagedown),
        LEFT("Left", R.string.key_label_left),
        RIGHT("Right", R.string.key_label_right),
        UP("Up", R.string.key_label_up),
        DOWN("Down", R.string.key_label_down),
        TAB("Tab", R.string.key_label_tab),
        RETURN("Return", R.string.key_label_enter),
        F1("F1", R.string.key_label_f1),
        F2("F2", R.string.key_label_f2),
        F3("F3", R.string.key_label_f3),
        F4("F4", R.string.key_label_f4),
        F5("F5", R.string.key_label_f5),
        F6("F6", R.string.key_label_f6),
        F7("F7", R.string.key_label_f7),
        F8("F8", R.string.key_label_f8),
        F9("F9", R.string.key_label_f9),
        F10("F10", R.string.key_label_f10),
        F11("F11", R.string.key_label_f11),
        F12("F12", R.string.key_label_f12);

        /** Resolved from [xkName] so the generated table stays the single source of keysym values. */
        val keysym: Long = requireNotNull(InputKeysyms.keysymFor(xkName)) { "unknown XK token: $xkName" }

        companion object {
            /** The entry for [keysym], or null when it is not one of the curated keys. */
            @JvmStatic
            fun of(keysym: Long): TriggerKey? = entries.firstOrNull { it.keysym == keysym }
        }
    }

    /** Supplies the persisted chord string for each [Action]; consumed by [Manager.from]. */
    fun interface ChordSource {
        fun chordFor(action: Action): String?
    }

    /**
     * A parsed chord: the ctrl/alt/shift modifier flags plus the RFB/X11 [keysym] of one trigger key
     * ([keysym] 0 -- which never matches -- means "not assigned"). Being a data class, its
     * value-based equality/hashCode let it serve directly as a [Manager] map key.
     */
    data class Chord(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val keysym: Long) {

        val isAssigned: Boolean get() = keysym != 0L

        /** Canonical persisted string: `Control_L+Alt_L+Shift_L+<XK token>`, or "" when unassigned. */
        override fun toString(): String {
            val token = InputKeysyms.nameOf(keysym) ?: return ""
            val b = StringBuilder()
            if (ctrl) b.append("Control_L+")
            if (alt) b.append("Alt_L+")
            if (shift) b.append("Shift_L+")
            b.append(token)
            return b.toString()
        }

        companion object {
            /**
             * Parses a persisted chord string. Tokens are separated by "+"; the modifier tokens (the
             * XK_ names Control_L/Control_R, Alt_L/Alt_R, Shift_L/Shift_R, prefix stripped -- either
             * side accepted, matched by exact case like the trigger) may appear in any order, and the
             * last other token is the trigger key (an XK_ name with the prefix stripped, also matched
             * by exact case -- see [InputKeysyms]). Empty / unknown trigger input yields an unassigned
             * chord.
             */
            @JvmStatic
            fun fromString(s: String?): Chord {
                var ctrl = false
                var alt = false
                var shift = false
                var keysym = 0L
                if (s != null) {
                    for (part in s.split('+')) {
                        val token = part.trim()
                        // modifiers match either side, by exact case like the trigger key
                        when (token) {
                            "" -> { /* skip empty tokens from a leading/trailing/double "+" */ }
                            "Control_L", "Control_R" -> ctrl = true
                            "Alt_L", "Alt_R" -> alt = true
                            "Shift_L", "Shift_R" -> shift = true
                            else -> keysym = InputKeysyms.keysymFor(token) ?: 0L
                        }
                    }
                }
                return Chord(ctrl, alt, shift, keysym)
            }
        }
    }

    /**
     * Holds the active chord bindings as a [Chord]-keyed map and resolves an incoming (modifier
     * state, trigger keysym) to the bound [Action]. Build one from the per-action chord strings with
     * [from]; it carries no Android state, so InputService can keep a single instance and swap it
     * when the settings change. Modifier matching is exact -- a bare trigger key and its modified
     * chord never collide because their [Chord] keys differ.
     */
    class Manager private constructor(
        private val bindings: Map<Chord, Action>,
        /**
         * Chords the source config assigned to more than one action. The settings UI consults this to
         * reject a duplicate assignment; at runtime the first action in [Action] declaration order
         * wins such a chord (see [from]).
         */
        val conflicts: Set<Chord>,
        /**
         * Chords the source config supplied for an action but that yielded no usable trigger -- a
         * misspelled or unknown key name, or modifiers with no trigger key. An empty string is a
         * deliberate "no shortcut" and is not reported here. Keyed by action, valued by the string as
         * given, so the caller can name both in a warning.
         */
        val unparsed: Map<Action, String>,
    ) {

        /**
         * Returns the action bound to the given (exact) modifier state and trigger keysym, or null if
         * nothing matches.
         */
        fun actionFor(ctrl: Boolean, alt: Boolean, shift: Boolean, keysym: Long): Action? =
            bindings[Chord(ctrl, alt, shift, keysym)]

        companion object {
            /**
             * Builds a manager by asking [source] for each [Action]'s persisted chord string (each a
             * "+"-string; unassigned / empty / unknown-trigger contributes no binding). When two
             * actions resolve to the same assigned chord the first in declaration order keeps it and
             * the chord is recorded in [conflicts]; a non-blank string that yields no trigger at all
             * is recorded in [unparsed]. Both are reported rather than logged so this stays free of
             * Android calls -- the caller decides how to surface them.
             */
            @JvmStatic
            fun from(source: ChordSource): Manager {
                val bindings = LinkedHashMap<Chord, Action>()
                val conflicts = LinkedHashSet<Chord>()
                val unparsed = LinkedHashMap<Action, String>()
                for (action in Action.entries) {
                    val given = source.chordFor(action)
                    val chord = Chord.fromString(given)
                    if (!chord.isAssigned) {
                        if (!given.isNullOrBlank()) {
                            unparsed[action] = given
                        }
                        continue
                    }
                    if (bindings.containsKey(chord)) {
                        conflicts.add(chord)
                    } else {
                        bindings[chord] = action
                    }
                }
                return Manager(bindings, conflicts, unparsed)
            }
        }
    }
}
