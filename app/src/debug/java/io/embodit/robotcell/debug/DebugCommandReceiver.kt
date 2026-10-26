package io.embodit.robotcell.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.robotcell.platform.CellLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.robotcell.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.robotcell.DEBUG -p io.embodit.robotcell --es cmd goto --es s S05"
 *
 * Every extra is read as text (string, float, int and boolean extras alike).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val extras = HashMap<String, String>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION") val v = bundle.get(key) ?: continue
            extras[key] = v.toString()
        }
        if (!extras.containsKey("cmd")) return
        CellLog.i("CMD recv $extras")
        DebugCommands.run(context.applicationContext, extras)
    }
}
