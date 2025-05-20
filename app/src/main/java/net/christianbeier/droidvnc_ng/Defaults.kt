/*
 * DroidVNC-NG defaults provider.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2023 Kitchen Armor.
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place Suite 330, Boston, MA 02111-1307, USA.
 */

package net.christianbeier.droidvnc_ng

import android.content.Context
import android.content.RestrictionsManager
import android.util.Log
import android.view.Display
import androidx.preference.PreferenceManager
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import androidx.core.content.edit


@OptIn(ExperimentalSerializationApi::class)
@Serializable
class Defaults {
    companion object {
        private const val TAG = "Defaults"
        private const val PREFS_KEY_DEFAULTS_ACCESS_KEY = "defaults_access_key"
    }

    @EncodeDefault
    var port = 5900
        private set

    @EncodeDefault
    var portReverse = 5500
        private set

    @EncodeDefault
    var portRepeater = 5500
        private set

    @EncodeDefault
    var scaling = 0.0f
        private set

    @EncodeDefault
    var viewOnly = false
        private set

    @EncodeDefault
    var showPointers = false
        private set

    @EncodeDefault
    var fileTransfer = true
        private set

    @EncodeDefault
    var password = ""
        private set

    @EncodeDefault
    var accessKey = ""
        private set

    @EncodeDefault
    var startOnBoot = true
        private set

    @EncodeDefault
    var startOnBootDelay = 0
        private set

    /*
       NB if adding fields here, don't forget to add their copying in the constructor as well!
     */

    constructor(context: Context) {
        /*
            persist randomly generated defaults
         */
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val defaultAccessKey = prefs.getString(PREFS_KEY_DEFAULTS_ACCESS_KEY, null)
        if (defaultAccessKey == null) {
            prefs.edit {
                putString(
                    PREFS_KEY_DEFAULTS_ACCESS_KEY,
                    UUID.randomUUID().toString().replace("-".toRegex(), "")
                )
            }
        }
        this.accessKey = prefs.getString(PREFS_KEY_DEFAULTS_ACCESS_KEY, null)!!

        /*
            Set default scaling according to device display density
         */
        this.scaling = 1.0f / Utils.getDisplayMetrics(context, Display.DEFAULT_DISPLAY).density.coerceAtLeast(1.0f)

        /*
            Try read defaults from app restrictions
         */
        val appConfig = (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager).applicationRestrictions
        if (appConfig != null && appConfig.size() > 0) {
            Log.i(TAG, "Loading defaults from app restrictions")
            this.port = appConfig.getInt("port", this.port)
            this.portReverse = appConfig.getInt("portReverse", this.portReverse)
            this.portRepeater = appConfig.getInt("portRepeater", this.portRepeater)
            this.fileTransfer = appConfig.getBoolean("fileTransfer", this.fileTransfer)
            this.viewOnly = appConfig.getBoolean("viewOnly", this.viewOnly)
            this.showPointers = appConfig.getBoolean("showPointers", this.showPointers)
            this.password = appConfig.getString("password", this.password) ?: this.password
            this.startOnBoot = appConfig.getBoolean("startOnBoot", this.startOnBoot)
            this.startOnBootDelay = appConfig.getInt("startOnBootDelay", this.startOnBootDelay)

            val scalingStr = appConfig.getString("scaling", "0.0")
            try {
                val scaling = scalingStr.toFloat()
                if (scaling > 0.0f)
                    this.scaling = scaling
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Invalid scaling value in app restrictions: $scalingStr")
            }

            val accessKey = appConfig.getString("accessKey", "")
            if (accessKey != null && accessKey.isNotEmpty())
                this.accessKey = accessKey

            return
        }

        /*
            read provided defaults
         */
        val jsonFile = File(context.getExternalFilesDir(null), "defaults.json")
        try {
            val jsonString = jsonFile.readText()
            val readDefault = Json.decodeFromString<Defaults>(jsonString)
            Log.i(TAG, "Loading defaults from json file")
            this.port = readDefault.port
            this.portReverse = readDefault.portReverse
            this.portRepeater = readDefault.portRepeater
            this.fileTransfer = readDefault.fileTransfer
            // only set new scaling if there is one given; i.e. don't overwrite generated default
            if(readDefault.scaling > 0)
                this.scaling = readDefault.scaling
            this.viewOnly = readDefault.viewOnly
            this.showPointers = readDefault.showPointers
            this.password = readDefault.password
            // only set new access key if there is one given; i.e. don't overwrite generated default
            // with empty string
            if (readDefault.accessKey != "")
                this.accessKey = readDefault.accessKey
            this.startOnBoot = readDefault.startOnBoot
            this.startOnBootDelay = readDefault.startOnBootDelay
            // add here!
        } catch (e: Exception) {
            Log.w(TAG, "${e.message}")
        }
    }

}

