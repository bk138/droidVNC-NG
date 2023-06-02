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
import android.util.Log
import java.io.File
import kotlinx.serialization.*
import kotlinx.serialization.json.*


@OptIn(ExperimentalSerializationApi::class)
@Serializable
class Defaults {
    companion object {
        private const val TAG = "Defaults"
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
    var scaling = 1.0f
        private set

    @EncodeDefault
    var password = ""
        private set
    /*
       NB if adding fields here, don't forget to add their copying in the constructor as well!
     */

    constructor(context: Context) {
        val jsonFile = File(context.getExternalFilesDir(null), "defaults.json")
        try {
            val jsonString = jsonFile.readText()
            val readDefault = Json.decodeFromString<Defaults>(jsonString)
            this.port = readDefault.port
            this.portReverse = readDefault.portReverse
            this.portRepeater = readDefault.portRepeater
            this.scaling = readDefault.scaling
            this.password = readDefault.password
            // add here!
        } catch (e: Exception) {
            Log.w(TAG, "${e.message}")
        }
    }

}

