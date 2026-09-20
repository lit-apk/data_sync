package top.lighilit.watch_data_sync

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {
    private const val PREFERENCES = "crash_reporter"
    private const val LAST_CRASH = "last_crash"

    fun install(context: Context) {
        val applicationContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_CRASH, format("Uncaught exception on ${thread.name}", throwable))
                .commit()
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun consumeLastCrash(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val crash = preferences.getString(LAST_CRASH, null)
        if (crash != null) preferences.edit().remove(LAST_CRASH).apply()
        return crash
    }

    fun format(source: String, throwable: Throwable): String {
        val trace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }
        return buildString {
            appendLine(source)
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            append(trace)
        }
    }
}
