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

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * Full-screen settings screen for the configurable keyboard shortcuts (issue #13). It inflates one
 * row per [InputKeyShortcut.Action] -- three modifier checkboxes (Ctrl/Alt/Shift) plus a trigger-key
 * [Spinner] -- and owns their whole lifecycle: loading the persisted chords, offering the
 * [InputKeyShortcut.TriggerKey] entries and their localized labels, rejecting a chord already assigned to another action, persisting a change and live-updating
 * the running [InputService]. It iterates the [InputKeyShortcut.Action] constants rather than listing
 * the actions here, so the caller only has to start it.
 */
class InputKeyShortcutSetupActivity : AppCompatActivity() {

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
        // On Android 15 and later, calling enableEdgeToEdge ensures system bar icon colors update
        // when the device theme changes. Because calling it on pre-Android 15 has the side effect of
        // enabling EdgeToEdge there as well, we only use it on Android 15 and later.
        if (Build.VERSION.SDK_INT >= 35) {
            this.enableEdgeToEdge()
        }
        setContentView(R.layout.activity_key_shortcut_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<Button>(R.id.key_shortcut_restore_defaults).setOnClickListener {
            // ask first: this discards every chord the user set, and there is no undo
            AlertDialog.Builder(this)
                .setMessage(R.string.main_activity_settings_key_shortcuts_restore_confirm)
                .setPositiveButton(R.string.main_activity_settings_key_shortcuts_restore) { _, _ -> restoreDefaults() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        setupRows()
    }

    /**
     * Drops every persisted chord so each action falls back to its default -- which is whatever
     * defaults.json / managed config sets, not necessarily the built-in chord -- then rebuilds the
     * rows from that.
     */
    private fun restoreDefaults() {
        val editor = PreferenceManager.getDefaultSharedPreferences(this).edit()
        for (action in InputKeyShortcut.Action.entries) {
            editor.remove(action.prefKey)
        }
        editor.apply()
        findViewById<LinearLayout>(R.id.key_shortcut_rows).removeAllViews()
        rows.clear()
        setupRows()
        // live-reload the running input service from prefs (a no-op when it is not connected)
        InputService.reloadShortcuts()
    }

    private fun setupRows() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaults = Defaults(this)
        val container = findViewById<LinearLayout>(R.id.key_shortcut_rows)
        val inflater = layoutInflater

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
     * Populates a row's key spinner with "None" and the curated [InputKeyShortcut.TriggerKey] entries
     * and, when [keysym] is an assigned key outside that set (e.g. a key bound through defaults.json
     * or managed config), a trailing entry labelled with that key's own XK name -- so it stays
     * visible and editable instead of collapsing to "None". [Row.keysyms] mirrors the resulting
     * adapter positions for read-back.
     */
    private fun bindKeyChoices(row: Row, keysym: Long) {
        val keys = InputKeyShortcut.TriggerKey.entries
        val labels = ArrayList<String>(keys.size + 2)
        val keysyms = ArrayList<Long>(keys.size + 2)
        labels.add(getString(R.string.key_label_none))
        keysyms.add(0L)
        for (key in keys) {
            labels.add(getString(key.labelRes))
            keysyms.add(key.keysym)
        }
        if (keysym != 0L && InputKeyShortcut.TriggerKey.of(keysym) == null) {
            labels.add(InputKeysyms.nameOf(keysym) ?: "0x" + keysym.toString(16))
            keysyms.add(keysym)
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
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
                    this,
                    getString(R.string.main_activity_settings_chord_conflict, value),
                    Toast.LENGTH_SHORT
                ).show()
                updating = true
                applyChord(row, InputKeyShortcut.Chord.fromString(row.selected)) // revert to last good
                updating = false
                return
            }
        }
        row.selected = value
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString(row.action.prefKey, value).apply()
        // live-reload the running input service from prefs (a no-op when it is not connected)
        InputService.reloadShortcuts()
    }

}
