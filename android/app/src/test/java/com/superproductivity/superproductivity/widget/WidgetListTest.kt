package com.superproductivity.superproductivity.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetListTest {

    private val blob =
        """
        {
          "v": 1,
          "tasks": [{"id": "today1", "title": "Today task", "isDone": false}],
          "lists": {
            "INBOX": [{"id": "inbox1", "title": "Inbox task", "isDone": false, "projectId": "INBOX_PROJECT"}]
          },
          "projectColors": {"INBOX_PROJECT": "#00ff00"}
        }
        """.trimIndent()

    private val blobWithoutLists = """{"v": 1, "tasks": [{"id": "t", "title": "T"}]}"""

    @Test
    fun todayReadsTopLevelTasks() {
        assertEquals(
            listOf("today1"),
            WidgetData.parse(blob, list = WidgetList.TODAY).map { it.id }
        )
    }

    @Test
    fun defaultsToToday() {
        assertEquals(listOf("today1"), WidgetData.parse(blob).map { it.id })
    }

    @Test
    fun inboxReadsItsListWithProjectColor() {
        assertEquals(
            listOf(WidgetTask("inbox1", "Inbox task", false, "#00ff00")),
            WidgetData.parse(blob, list = WidgetList.INBOX)
        )
    }

    @Test
    fun inboxOverlaysPendingDoneTaps() {
        val tasks = WidgetData.parse(blob, mapOf("inbox1" to true), WidgetList.INBOX)
        assertTrue(tasks.single().isDone)
    }

    @Test
    fun inboxIsEmptyForBlobWrittenBeforeListsExisted() {
        assertTrue(WidgetData.parse(blobWithoutLists, list = WidgetList.INBOX).isEmpty())
    }

    @Test
    fun fromIdResolvesKnownIds() {
        WidgetList.entries.forEach { assertEquals(it, WidgetList.fromId(it.id)) }
    }

    @Test
    fun fromIdFallsBackToDefault() {
        assertEquals(WidgetList.DEFAULT, WidgetList.fromId(null))
        assertEquals(WidgetList.DEFAULT, WidgetList.fromId("REMOVED_LIST"))
    }

    @Test
    fun eachListOpensItsOwnQuickActionHost() {
        assertEquals("com.super-productivity.app://today", WidgetList.TODAY.appUri)
        assertEquals("com.super-productivity.app://inbox", WidgetList.INBOX.appUri)
    }

    @Test
    fun eachListHasItsOwnIcon() {
        assertEquals(
            WidgetList.entries.size,
            WidgetList.entries.map { it.iconRes }.toSet().size
        )
    }

    @Test
    fun onlyTodayIsDayBound() {
        assertTrue(WidgetList.TODAY.isDayBound)
        assertFalse(WidgetList.INBOX.isDayBound)
    }

    private val mixedCaptures =
        """
        [
          {"id": "legacy", "title": "No list"},
          {"id": "today", "title": "Today", "list": "TODAY"},
          {"id": "inbox", "title": "Inbox", "list": "INBOX"},
          {"id": "removed", "title": "Removed", "list": "REMOVED_LIST"}
        ]
        """.trimIndent()

    @Test
    fun pendingCapturesShowOnlyInTheirList() {
        assertEquals(
            listOf("inbox"),
            WidgetData.withPendingCaptures(emptyList(), mixedCaptures, WidgetList.INBOX)
                .map { it.id }
        )
    }

    @Test
    fun capturesWithoutKnownListShowInToday() {
        assertEquals(
            listOf("removed", "today", "legacy"),
            WidgetData.withPendingCaptures(emptyList(), mixedCaptures, WidgetList.TODAY)
                .map { it.id }
        )
    }
}
