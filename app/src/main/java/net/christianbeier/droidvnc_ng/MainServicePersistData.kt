package net.christianbeier.droidvnc_ng

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object MainServicePersistData {

    private const val PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_START_INTENT =
        "main_service_persist_data_start_intent"
    private const val PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_LAST_ACTIVE_STATE =
        "main_service_persist_data_last_active_state"

    /**
     * Clears all saved state.
     */
    @JvmStatic
    fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            remove(PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_START_INTENT)
            remove(PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_LAST_ACTIVE_STATE)
        }
    }

    /**
     * Saves Start Intent.
     */
    @JvmStatic
    fun saveStartIntent(context: Context, intent: Intent) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(
                PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_START_INTENT,
                intent.toUri(0)
            )
        }
    }

    /**
     * Loads Start Intent, null if none saved.
     */
    @JvmStatic
    fun loadStartIntent(context: Context): Intent? {
        val intentUri = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_START_INTENT, null)
        return if(intentUri != null) {
            Intent.parseUri(
                intentUri, 0
            )
        } else {
            null
        }
    }

    /**
     * Saves if VNC server is running or not.
     */
    @JvmStatic
    fun saveLastActiveState(context: Context, isActive: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(
                PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_LAST_ACTIVE_STATE,
                isActive
            )
        }
    }

    /**
     * Loads if VNC server was running or not.
     */
    @JvmStatic
    fun loadLastActiveState(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREFS_KEY_MAIN_SERVICE_PERSIST_DATA_LAST_ACTIVE_STATE, false)
    }

}
