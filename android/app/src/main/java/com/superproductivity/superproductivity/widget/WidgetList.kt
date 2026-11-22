package com.superproductivity.superproductivity.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.superproductivity.superproductivity.R

/**
 * Lists the widget can show. To add one: add an entry here, emit its tasks under
 * `lists.<id>` in selectAndroidWidgetPayload and add its quick-add target to
 * CAPTURE_TARGETS in android-capture-import.service.ts (AndroidWidgetExtraListId).
 *
 * @param id persisted per widget and in captures, and the blob key — never rename
 * @param appUri opens this list in the app; host must be in parse-app-uri-quick-action.ts
 * @param isDayBound the list describes one logical day, so its header can go outdated
 */
enum class WidgetList(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val emptyRes: Int,
    @DrawableRes val iconRes: Int,
    val appUri: String,
    val isDayBound: Boolean = false
) {
    TODAY(
        id = "TODAY",
        titleRes = R.string.widget_header_title,
        emptyRes = R.string.widget_empty,
        iconRes = R.drawable.ic_widget_list_today,
        appUri = "com.super-productivity.app://today",
        isDayBound = true
    ),
    INBOX(
        id = "INBOX",
        titleRes = R.string.widget_list_inbox,
        emptyRes = R.string.widget_empty_inbox,
        iconRes = R.drawable.ic_widget_list_inbox,
        appUri = "com.super-productivity.app://inbox"
    );

    companion object {
        val DEFAULT = TODAY

        /** Falls back so a list removed in a later version cannot break the widget. */
        fun fromId(id: String?): WidgetList = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Per-widget selection, so two widgets on the home screen can show different lists. */
object WidgetListStore {
    private const val PREFS_NAME = "widget_list_selection"

    private fun key(appWidgetId: Int) = "list_$appWidgetId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): WidgetList =
        WidgetList.fromId(prefs(context).getString(key(appWidgetId), null))

    fun set(context: Context, appWidgetId: Int, list: WidgetList) {
        prefs(context).edit().putString(key(appWidgetId), list.id).apply()
    }

    fun remove(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        appWidgetIds.forEach { editor.remove(key(it)) }
        editor.apply()
    }
}
