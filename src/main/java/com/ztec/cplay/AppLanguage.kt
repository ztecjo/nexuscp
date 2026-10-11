// SPDX-License-Identifier: GPL-3.0-only
package com.ztec.cplay

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

internal object AppLanguage {
    val choices = listOf("", "en", "ar")
    private const val PREFS = "app_language"

    fun selected(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("migrated", false)) {
                val previous = prefs.getString("language", "") ?: ""
                if (manager.applicationLocales.isEmpty && previous in choices && previous.isNotEmpty()) {
                    manager.applicationLocales = LocaleList(Locale.forLanguageTag(previous))
                }
                prefs.edit().putBoolean("migrated", true).remove("language").apply()
            }
            val locales = manager.applicationLocales
            return if (locales.isEmpty) "" else locales[0].language
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("language", "")?.takeIf { it in choices } ?: ""
    }

    fun save(context: Context, language: String) {
        require(language in choices)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList(Locale.forLanguageTag(language))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("migrated", true).remove("language").apply()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", language).apply()
        }
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            selected(context)
            return context
        }
        val language = selected(context)
        if (language.isEmpty()) return context
        val locale = Locale.forLanguageTag(language)
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        })
    }

    fun name(context: Context, language: String): String = when (language) {
        "en" -> "English"
        "ar" -> "العربية"
        else -> context.getString(R.string.language_system)
    }

    fun showPicker(activity: Activity) {
        var selection = choices.indexOf(selected(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity).setTitle(R.string.app_language)
            .setSingleChoiceItems(choices.map { name(activity, it) }.toTypedArray(), selection) { _, index -> selection = index }
            .setPositiveButton(R.string.save) { _, _ ->
                val language = choices[selection]
                if (language != selected(activity)) {
                    save(activity, language)
                    if (Build.VERSION.SDK_INT < 33) activity.recreate()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
