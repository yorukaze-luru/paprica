package app.webgen.generator

import android.content.Context
import android.content.res.Configuration

/**
 * Theme (dark mode) support for the generator app, v1.5.
 *
 * The mode is stored in the "settings" prefs. Activities wrap their
 * base context with a uiMode override (see attachBaseContext), which
 * makes the framework pick the values-night / drawable-night resource
 * variants. The base theme stays the light Material theme on purpose
 * (v1.4.1 lesson: overriding theme text colors resolved badly on a
 * real device), so dialogs keep their default readable styling and
 * all in-layout text uses explicit colors that have night variants.
 */
object ThemePrefs {

    const val KEY_THEME_MODE = "theme_mode"
    const val MODE_AUTO = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun getMode(ctx: Context): Int =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, MODE_AUTO)

    fun setMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    fun wrap(base: Context): Context {
        val mode = getMode(base)
        if (mode == MODE_AUTO) return base
        val night = if (mode == MODE_DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
