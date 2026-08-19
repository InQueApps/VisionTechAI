package ab.inque.visiontechai

import android.content.Context

/**
 * S42: Persist in-flight SAVE across short process interruptions (best-effort).
 * Native stitch state cannot survive process death — we only record intent and notify on restart.
 */
object SphereExportPersistence {

    private const val PREFS = "sphere_export_state"
    private const val KEY_PENDING = "pending_background_save"
    private const val KEY_STARTED_AT = "started_at_ms"

    fun markPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, true)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING)
            .remove(KEY_STARTED_AT)
            .apply()
    }

    fun consumeInterruptedExport(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasPending = prefs.getBoolean(KEY_PENDING, false)
        if (wasPending) {
            prefs.edit().remove(KEY_PENDING).remove(KEY_STARTED_AT).apply()
        }
        return wasPending
    }
}
