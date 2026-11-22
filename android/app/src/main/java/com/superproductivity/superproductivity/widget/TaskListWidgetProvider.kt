package com.superproductivity.superproductivity.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.superproductivity.superproductivity.App
import com.superproductivity.superproductivity.AppShortcutActivity
import com.superproductivity.superproductivity.CapacitorMainActivity
import com.superproductivity.superproductivity.R

/**
 * Home screen widget listing the selected [WidgetList] from the `widget_data`
 * KeyValStore snapshot pushed by Angular. Checkbox taps enqueue the task ID in
 * [WidgetDoneQueue]; Angular drains the queue (instantly via the local drain
 * broadcast when alive, otherwise on next resume/cold start).
 */
class TaskListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetListStore.remove(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CLICK) {
            return
        }
        // A collection view has a single PendingIntent template, so both row
        // outcomes arrive here; the fill-in extras decide which one was tapped.
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        when {
            taskId != null -> {
                // Target state computed at render time from the DISPLAYED state
                // (incl. pending overlay), so repeated taps toggle back and forth.
                val setDone = intent.getBooleanExtra(EXTRA_SET_DONE, true)
                Log.d(TAG, "Toggle done from widget: taskId=$taskId setDone=$setDone")
                WidgetDoneQueue.setTarget(context, taskId, setDone)
                // Re-render so the pending-done overlay shows the checked box. Full
                // refresh, not rows-only: the tap cannot change the blob, but the
                // verdict is a function of *now*, and this is the one interaction that
                // reaches our code while the app process is dead — so a tap on a new
                // day must not redraw rows under a header still claiming "Today".
                refreshAll(context)
                // Contentless "drain now" signal for a live app; Angular always
                // pulls the IDs from the queue itself (single delivery path).
                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent(ACTION_WIDGET_DONE_DRAIN))
            }

            intent.getBooleanExtra(EXTRA_OPEN_APP, false) -> {
                try {
                    context.startActivity(
                        Intent(context, CapacitorMainActivity::class.java).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                } catch (e: Exception) {
                    // Background-activity-launch restrictions may block this on some
                    // API levels/OEMs; the header tap (direct activity PendingIntent)
                    // remains as the reliable way in.
                    Log.w(TAG, "Failed to open app from widget row tap", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "TaskListWidget"
        const val ACTION_CLICK = "com.superproductivity.superproductivity.WIDGET_CLICK"
        const val ACTION_WIDGET_DONE_DRAIN =
            "com.superproductivity.superproductivity.WIDGET_DONE_DRAIN"
        const val EXTRA_TASK_ID = "WIDGET_TASK_ID"
        const val EXTRA_SET_DONE = "WIDGET_SET_DONE"
        const val EXTRA_OPEN_APP = "WIDGET_OPEN_APP"

        private fun widgetIds(context: Context, appWidgetManager: AppWidgetManager): IntArray =
            appWidgetManager.getAppWidgetIds(
                ComponentName(context, TaskListWidgetProvider::class.java)
            )

        /**
         * "Today" while the snapshot still describes the current logical day,
         * otherwise the snapshot's own date. Only Angular can compute today's list —
         * today's repeat instances do not exist as entities until its day-change
         * effects have run, and overdue tasks are carried over there too — so a
         * process that stayed dead across midnight leaves yesterday's blob in place.
         * Name the day actually on screen rather than mislabelling it "Today" (#9098).
         *
         * Reads the blob itself: the header lives in the provider's RemoteViews while
         * the rows are built in a separate RemoteViewsFactory, with no shared lifetime
         * to hand it down. Call once per refresh — the result is the same for every
         * widget id. Also returns the current-task row so both read the same blob
         * string instead of hitting the KeyValStore twice per refresh.
         */
        private fun headerAndCurrentTask(context: Context): Pair<CharSequence, WidgetCurrentTask?> {
            val json = try {
                (context.applicationContext as App).keyValStore.get(WidgetData.KEYVAL_KEY, "{}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read widget data for header", e)
                // Unknown stamp: keep the pre-#9098 behaviour rather than cry stale.
                return context.getString(R.string.widget_header_title) to null
            }
            val meta = WidgetData.parseMeta(json)
            val nowMs = System.currentTimeMillis()
            // The verdict lives in WidgetData.headerFor (pure, tested); this only renders it.
            val header = when (val h = WidgetData.headerFor(meta, nowMs)) {
                is WidgetHeader.Today -> context.getString(R.string.widget_header_title)
                is WidgetHeader.Outdated -> h.dayMs?.let { dayMs ->
                    context.getString(
                        R.string.widget_header_outdated,
                        DateUtils.formatDateTime(
                            context,
                            dayMs,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_ABBREV_WEEKDAY
                        )
                    )
                } ?: context.getString(R.string.widget_header_outdated_unknown)
            }
            return header to WidgetData.parseCurrentTask(json, nowMs)
        }

        /**
         * Refreshes rows and header — every caller needs both. A push can change the day
         * the blob describes (it is how a widget stops being outdated), and a tap, though
         * it cannot change the blob, still re-renders at a later *now* than the last
         * verdict was computed at. The header is not part of the collection, so neither
         * can be a rows-only reload.
         *
         * A full update is deliberate, not lazy. It costs a few PendingIntents on a
         * debounced-and-deduped path, and it does NOT cost scroll position: the host
         * reapplies onto the recycled view (same layout id) and AbsListView keeps the
         * bound adapter when the adapter intent is unchanged, which it always is here.
         * The obvious "cheaper" partiallyUpdateAppWidget is a trap — despite its docs it
         * does not ignore a widget with no cached views, it *replaces* them, so it would
         * install a header with no adapter and no click targets on any widget whose views
         * the system has dropped (an app upgrade clears them explicitly).
         *
         * @param scrollToTopWidgetId the widget whose list just changed: the kept scroll
         * offset belongs to the previous list
         */
        fun refreshAll(context: Context, scrollToTopWidgetId: Int? = null) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context, appWidgetManager)
            // Every push reaches here; without a widget there is nothing to read for.
            if (ids.isEmpty()) {
                return
            }
            updateAll(context, appWidgetManager, ids, scrollToTopWidgetId)
        }

        /** Rebuilds each passed widget, reading the header once for all of them. */
        private fun updateAll(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            scrollToTopWidgetId: Int? = null
        ) {
            val (todayHeader, currentTask) = headerAndCurrentTask(context)
            // setRemoteAdapter alone does not re-invoke the factory's onDataSetChanged()
            // when the adapter intent is unchanged (it always is — same widget id, same
            // Uri), so the rows would otherwise be whatever the adapter last built.
            // Must precede updateAppWidget: in the reverse order Android 16's Pixel
            // launcher (API 36.1) keeps the old header and applies only the new rows.
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_task_list)
            for (appWidgetId in appWidgetIds) {
                updateWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    todayHeader,
                    currentTask,
                    isScrollToTop = appWidgetId == scrollToTopWidgetId
                )
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            todayHeader: CharSequence,
            currentTask: WidgetCurrentTask?,
            isScrollToTop: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_task_list)
            val list = WidgetListStore.get(context, appWidgetId)

            views.setImageViewResource(R.id.widget_header_icon, list.iconRes)
            views.setTextViewText(
                R.id.widget_header_title,
                if (list.isDayBound) todayHeader else context.getString(list.titleRes)
            )
            views.setTextViewText(R.id.widget_empty, context.getString(list.emptyRes))

            if (currentTask != null) {
                views.setViewVisibility(R.id.widget_tracking_row, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_tracking_title,
                    context.getString(R.string.widget_tracking_title, currentTask.title)
                )
                views.setTextViewText(
                    R.id.widget_tracking_device,
                    context.getString(R.string.widget_tracking_device, currentTask.deviceLabel)
                )
            } else {
                views.setViewVisibility(R.id.widget_tracking_row, View.GONE)
            }

            val serviceIntent = Intent(context, TaskListWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_task_list, serviceIntent)
            views.setEmptyView(R.id.widget_task_list, R.id.widget_empty)
            if (isScrollToTop) {
                views.setScrollPosition(R.id.widget_task_list, 0)
            }

            // Single template for all row clicks (explicit component — needs no
            // manifest intent-filter entry). MUTABLE is required for fill-ins.
            val clickIntent = Intent(context, TaskListWidgetProvider::class.java).apply {
                action = ACTION_CLICK
            }
            val clickPendingIntent = PendingIntent.getBroadcast(
                context, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_task_list, clickPendingIntent)

            // Tracking row tap → open app
            val openAppIntent = Intent(context, CapacitorMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_tracking_row, openAppPendingIntent)

            // Header tap → the shown list. Via the shortcut trampoline, which forwards
            // the deep link without reloading a running app's WebView.
            val openListIntent = Intent(context, AppShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(list.appUri)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val openListPendingIntent = PendingIntent.getActivity(
                context, 0, openListIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header, openListPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_empty, openListPendingIntent)

            // Dropdown arrow → picker. Request code per widget: PendingIntents differing
            // only in extras would otherwise collapse into one.
            val pickerIntent = Intent(context, WidgetListPickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widget_list_picker,
                PendingIntent.getActivity(
                    context, appWidgetId, pickerIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // "+" → quick add dialog; the app itself stays closed.
            val quickAddIntent = Intent(context, QuickAddActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val quickAddPendingIntent = PendingIntent.getActivity(
                context, appWidgetId, quickAddIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_add, quickAddPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
