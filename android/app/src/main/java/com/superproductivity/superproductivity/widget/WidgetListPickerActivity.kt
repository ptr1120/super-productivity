package com.superproductivity.superproductivity.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.superproductivity.superproductivity.R

/** Radio list over the home screen, opened by the dropdown arrow in the widget header. */
class WidgetListPickerActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        check(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            "WidgetListPickerActivity started without a widget id"
        }

        val lists = WidgetList.entries
        val labels = lists.map { getString(it.titleRes) }.toTypedArray<CharSequence>()
        val selected = lists.indexOf(WidgetListStore.get(this, appWidgetId))

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.widget_list_picker_title)
            .setSingleChoiceItems(labels, selected) { picker, which ->
                WidgetListStore.set(this, appWidgetId, lists[which])
                // Own try: the selection is saved, so a failed redraw must not crash.
                try {
                    TaskListWidgetProvider.refreshAll(this, scrollToTopWidgetId = appWidgetId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh widget after list change", e)
                }
                picker.dismiss()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onDestroy() {
        // Rotation or noHistory teardown would otherwise leak the dialog's window.
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SP_WIDGET_LIST"
    }
}
