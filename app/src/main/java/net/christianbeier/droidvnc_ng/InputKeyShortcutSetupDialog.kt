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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar

/**
 * Full-screen dialog for the configurable keyboard shortcuts (issue #13). It inflates one row per
 * [InputKeyShortcut.Action] -- three modifier checkboxes (Ctrl/Alt/Shift) plus a trigger-key
 * [Spinner] -- and owns their whole lifecycle: loading the persisted chords, offering localized key
 * labels, rejecting a chord already assigned to another action, persisting a change and live-updating
 * the running [InputService]. It iterates the [InputKeyShortcut.Action] constants rather than listing
 * the actions here, so the caller only has to construct and [show] it.
 */
class InputKeyShortcutSetupDialog(context: Context) : Dialog(context, R.style.FullScreenDialog) {

    /** Runtime state for one action row: its action, resolved controls and last-good chord. */
    private class Row(
        val action: InputKeyShortcut.Action,
        val ctrl: CheckBox,
        val alt: CheckBox,
        val shift: CheckBox,
        val key: Spinner,
    ) {
        /** Last-good canonical chord string ("" when unassigned); the revert target on a conflict. */
        var selected: String = ""

        /** Keysym for each key-spinner position, parallel to its adapter (a trailing custom entry
         *  is appended when the bound chord uses a key outside the curated list). */
        var keysyms: List<Long> = emptyList()
    }

    private val rows = ArrayList<Row>(InputKeyShortcut.Action.entries.size)

    /** True while programmatically setting control state, so the change listeners stay quiet. */
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_key_shortcut_setup)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // The toolbar's navigation icon dismisses the dialog, matching the system Back button.
        findViewById<MaterialToolbar>(R.id.key_shortcut_setup_toolbar)
            .setNavigationOnClickListener { dismiss() }
        setupRows()
    }

    private fun setupRows() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val defaults = Defaults(context)
        val container = findViewById<LinearLayout>(R.id.key_shortcut_rows)
        val inflater = LayoutInflater.from(context)

        // inflate a row per action and set its initial state with the listeners still detached
        updating = true
        for (action in InputKeyShortcut.Action.entries) {
            val view = inflater.inflate(R.layout.key_shortcut_row, container, false)
            view.findViewById<TextView>(R.id.key_shortcut_label).setText(action.labelRes)
            val row = Row(
                action,
                view.findViewById(R.id.key_shortcut_ctrl),
                view.findViewById(R.id.key_shortcut_alt),
                view.findViewById(R.id.key_shortcut_shift),
                view.findViewById(R.id.key_shortcut_key),
            )
            val chord = InputKeyShortcut.Chord.fromString(prefs.getString(action.prefKey, action.defaultChord(defaults)))
            bindKeyChoices(row, chord.keysym)
            row.selected = chord.toString()
            applyChord(row, chord)
            container.addView(view)
            rows.add(row)
        }
        updating = false

        // attach the change listeners now that the initial state is in place
        for (i in rows.indices) {
            val row = rows[i]
            val onChecked = CompoundButton.OnCheckedChangeListener { _, _ -> onChordChanged(i) }
            row.ctrl.setOnCheckedChangeListener(onChecked)
            row.alt.setOnCheckedChangeListener(onChecked)
            row.shift.setOnCheckedChangeListener(onChecked)
            row.key.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                    onChordChanged(i)

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    /**
     * Populates a row's key spinner with the curated [KEY_CHOICES] and, when [keysym] is an assigned
     * key outside that list (e.g. an exotic key set via managed config), a trailing entry labelled
     * with that key's own XK token -- so it stays visible and editable instead of collapsing to
     * "None". [Row.keysyms] mirrors the resulting adapter positions for read-back.
     */
    private fun bindKeyChoices(row: Row, keysym: Long) {
        val labels = ArrayList<String>(KEY_CHOICES.size + 1)
        val keysyms = ArrayList<Long>(KEY_CHOICES.size + 1)
        for (choice in KEY_CHOICES) {
            labels.add(context.getString(choice.labelRes))
            keysyms.add(choice.keysym)
        }
        if (keysym != 0L && KEY_CHOICES.none { it.keysym == keysym }) {
            labels.add(InputKeysyms.nameOf(keysym) ?: "0x" + keysym.toString(16))
            keysyms.add(keysym)
        }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        row.key.adapter = adapter
        row.keysyms = keysyms
    }

    /** Sets a row's modifier checkboxes and key spinner from a parsed chord. Mute listeners first. */
    private fun applyChord(row: Row, chord: InputKeyShortcut.Chord) {
        row.ctrl.isChecked = chord.ctrl
        row.alt.isChecked = chord.alt
        row.shift.isChecked = chord.shift
        row.key.setSelection(row.keysyms.indexOf(chord.keysym).coerceAtLeast(0))
    }

    /** Reads a row's controls into a [InputKeyShortcut.Chord]. */
    private fun readChord(row: Row): InputKeyShortcut.Chord =
        InputKeyShortcut.Chord(row.ctrl.isChecked, row.alt.isChecked, row.shift.isChecked,
            row.keysyms[row.key.selectedItemPosition])

    /**
     * Handles a change on row [idx]: recompose the chord; if it duplicates another action's assigned
     * chord, toast and revert; otherwise persist it and live-reload the running [InputService].
     */
    private fun onChordChanged(idx: Int) {
        if (updating) {
            return
        }
        val row = rows[idx]
        val chord = readChord(row)
        val value = chord.toString()
        if (value == row.selected) {
            return // no actual change (e.g. a re-layout or unchanged re-selection callback)
        }
        // Reject an assigned chord another action already uses: rebuild the manager over the proposed
        // assignment (this row's new value, the rest as-is) and let it flag the duplicate.
        if (chord.isAssigned) {
            val proposed = InputKeyShortcut.Manager.from { a ->
                if (a == row.action) value else rows[a.ordinal].selected
            }
            if (chord in proposed.conflicts) {
                Toast.makeText(
                    context,
                    context.getString(R.string.main_activity_settings_chord_conflict, value),
                    Toast.LENGTH_SHORT
                ).show()
                updating = true
                applyChord(row, InputKeyShortcut.Chord.fromString(row.selected)) // revert to last good
                updating = false
                return
            }
        }
        row.selected = value
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(row.action.prefKey, value).apply()
        // live-reload the running input service from prefs (a no-op when it is not connected)
        InputService.reloadShortcuts()
    }

    private companion object {
        /** One entry in the curated trigger-key spinner: its keysym (0 = "None") and label. */
        private class KeyChoice(val keysym: Long, val labelRes: Int)

        /** Resolves an exact-case XK_ token to a [KeyChoice]; "" -> the "None" entry. */
        private fun keyChoice(token: String, labelRes: Int) =
            KeyChoice(
                if (token.isEmpty()) 0L
                else requireNotNull(InputKeysyms.keysymFor(token)) { "unknown XK token: $token" },
                labelRes
            )

        /**
         * Curated trigger keys offered in the UI spinner (declaration order = spinner order). Tokens
         * are X11 XK_ names (exact case) resolved against the generated [InputKeysymTable]; power users
         * can bind any other key via the Defaults / managed-config chord string.
         */
        private val KEY_CHOICES = listOf(
            keyChoice("", R.string.key_label_none),
            keyChoice("Home", R.string.key_label_home),
            keyChoice("End", R.string.key_label_end),
            keyChoice("Escape", R.string.key_label_esc),
            keyChoice("Delete", R.string.key_label_del),
            keyChoice("Insert", R.string.key_label_ins),
            keyChoice("BackSpace", R.string.key_label_backspace),
            keyChoice("Page_Up", R.string.key_label_pageup),
            keyChoice("Page_Down", R.string.key_label_pagedown),
            keyChoice("Left", R.string.key_label_left),
            keyChoice("Right", R.string.key_label_right),
            keyChoice("Up", R.string.key_label_up),
            keyChoice("Down", R.string.key_label_down),
            keyChoice("Tab", R.string.key_label_tab),
            keyChoice("Return", R.string.key_label_enter),
            keyChoice("F1", R.string.key_label_f1),
            keyChoice("F2", R.string.key_label_f2),
            keyChoice("F3", R.string.key_label_f3),
            keyChoice("F4", R.string.key_label_f4),
            keyChoice("F5", R.string.key_label_f5),
            keyChoice("F6", R.string.key_label_f6),
            keyChoice("F7", R.string.key_label_f7),
            keyChoice("F8", R.string.key_label_f8),
            keyChoice("F9", R.string.key_label_f9),
            keyChoice("F10", R.string.key_label_f10),
            keyChoice("F11", R.string.key_label_f11),
            keyChoice("F12", R.string.key_label_f12),
        )
    }
}
