package com.ztec.cplay

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppLanguageTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    @Test fun pickerAndAndroidSettingsShareTheSamePreference() {
        AppLanguage.save(context, "ar")
        val manager = context.getSystemService(LocaleManager::class.java)
        assertEquals("ar", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList(Locale.ENGLISH)
        assertEquals("en", AppLanguage.selected(context))
        AppLanguage.save(context, "")
        assertTrue(manager.applicationLocales.isEmpty)
        assertEquals("", AppLanguage.selected(context))
    }

    @Test @Config(sdk = [28]) fun olderAndroidAppliesArabicAndRtlWithoutChangingSystemResources() {
        val original = context.resources.configuration.locales[0]
        AppLanguage.save(context, "ar")
        val arabic = AppLanguage.wrap(context)
        assertEquals("ar", arabic.resources.configuration.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_RTL, arabic.resources.configuration.layoutDirection)
        assertEquals(original, context.resources.configuration.locales[0])
        AppLanguage.save(context, "en")
        assertEquals(View.LAYOUT_DIRECTION_LTR, AppLanguage.wrap(context).resources.configuration.layoutDirection)
        AppLanguage.save(context, "")
        assertSame(context, AppLanguage.wrap(context))
    }

    @Test fun legacyPreferenceMigratesOnceWithoutOverridingAndroidSettings() {
        val prefs = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
        prefs.edit().putString("language", "ar").commit()
        assertEquals("ar", AppLanguage.selected(context))
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        assertEquals("", AppLanguage.selected(context))
        prefs.edit().putBoolean("migrated", false).putString("language", "ar").commit()
        manager.applicationLocales = LocaleList(Locale.ENGLISH)
        assertEquals("en", AppLanguage.selected(context))
    }

    @Test(expected = IllegalArgumentException::class) fun unsupportedLanguageIsRejected() {
        AppLanguage.save(context, "invalid")
    }
}
