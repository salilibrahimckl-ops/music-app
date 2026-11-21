package com.salil.tarang

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/** Saves any crash so the app can show it on next launch (helps fixing bugs). */
class TarangApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                getSharedPreferences("tarang", MODE_PRIVATE).edit()
                    .putString("lastCrash", sw.toString().take(4000))
                    .commit()
            } catch (e: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }
}
