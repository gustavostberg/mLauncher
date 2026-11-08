package com.gustav.mlauncher

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gustav.mlauncher.data.LauncherPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarPanelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val preferences = LauncherPreferences(context)
    private val title = "WIDGET TEST ${UUID.randomUUID()}"
    private val ids = mutableListOf<Long>()
    private var wasEnabled = false
    private var calendarId = -1L

    @Before fun setUp() {
        wasEnabled = preferences.loadHomeIntegrationEnabled()
        preferences.saveHomeIntegrationEnabled(true)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id"), "account_type=?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL), null)!!.use {
            assertTrue("A local test calendar is required", it.moveToFirst())
            calendarId = it.getLong(0)
        }
    }

    @After fun tearDown() {
        try {
            ids.forEach { resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null) }
            preferences.saveHomeIntegrationEnabled(wasEnabled)
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    @Test fun widgetUpdatesCreateEditAndDeleteWithoutReopeningHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = create(System.currentTimeMillis() + 60_000L, System.currentTimeMillis() + 3_600_000L)
            await(scenario) { title in it }
            resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                ContentValues().apply { put(CalendarContract.Events.TITLE, "$title edited") }, null, null)
            await(scenario) { "$title edited" in it }
            resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            await(scenario) { title !in it }
        }
    }

    @Test fun latestChangeWinsDuringBurstOfUpdates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = create(System.currentTimeMillis() + 60_000L, System.currentTimeMillis() + 3_600_000L)
            repeat(8) { index ->
                resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    ContentValues().apply { put(CalendarContract.Events.TITLE, "$title update-$index") }, null, null)
            }
            await(scenario) { "$title update-7" in it }
        }
    }

    @Test fun expiredMeetingDisappearsOnClockRefresh() {
        val end = System.currentTimeMillis() + 10_000L
        val id = create(System.currentTimeMillis() - 60_000L, end)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { title in it }
            while (System.currentTimeMillis() <= end) SystemClock.sleep(100)
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("clockTicker").apply { isAccessible = true }
                (field.get(activity) as Runnable).run()
            }
            await(scenario) { title !in it }
            assertTrue(ids.contains(id))
        }
    }

    @Test fun calendarLineBudgetDoesNotLeaveAnOrphanHeading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val eventClass = Class.forName("com.gustav.mlauncher.MainActivity\$SystemCalendarEvent")
                val constructor = eventClass.getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                val day = LocalDate.now()
                val zone = ZoneId.systemDefault()
                val start = day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
                val events = (0..7).map { constructor.newInstance("Test $it", start + it * 60_000L, start + (it + 1) * 60_000L, false) } +
                    constructor.newInstance("Tomorrow", day.plusDays(1).atTime(14, 0).atZone(zone).toInstant().toEpochMilli(),
                        day.plusDays(1).atTime(15, 0).atZone(zone).toInstant().toEpochMilli(), false)
                val method = MainActivity::class.java.getDeclaredMethod("buildCalendarPanelLines", List::class.java, LocalDate::class.java)
                    .apply { isAccessible = true }
                val lines = method.invoke(activity, events, day) as List<*>
                assertEquals(9, lines.size)
                val last = lines.last()!!
                assertFalse(last.javaClass.getDeclaredField("isHeading").apply { isAccessible = true }.getBoolean(last))
            }
        }
    }

    @Test fun calendarPanelFitsPhoneAndCanBeRendered() {
        create(System.currentTimeMillis() + 60_000L, System.currentTimeMillis() + 3_600_000L)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { title in it }
            scenario.onActivity { activity ->
                val calendar = activity.findViewById<View>(R.id.calendarPanel)
                val gtd = activity.findViewById<View>(R.id.gtdPanel)
                assertTrue(calendar.height > 0 && calendar.width > 0)
                assertTrue(calendar.bottom <= gtd.top)
                val items = activity.findViewById<ViewGroup>(R.id.calendarItemsContainer)
                (0 until items.childCount).map { items.getChildAt(it) }.filterIsInstance<ViewGroup>().forEach { row ->
                    val heading = row.getChildAt(0) as TextView
                    assertEquals("Date heading must remain readable", 0, heading.layout.getEllipsisCount(0))
                }
                val root = activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "calendar-widget-test.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun expandedWidgetReportsItsActualHeight() {
        val widgetId = preferences.loadHomeWidgetId()
        assumeNotNull(widgetId)
        val wasExpanded = preferences.loadHomeWidgetExpanded()
        try {
            preferences.saveHomeWidgetExpanded(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val options = AppWidgetManager.getInstance(activity).getAppWidgetOptions(widgetId!!)
                    val expected = (activity.resources.getDimension(R.dimen.home_widget_height_expanded) /
                        activity.resources.displayMetrics.density).toInt()
                    assertEquals(expected, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
                    assertEquals(expected, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
                }
            }
        } finally { preferences.saveHomeWidgetExpanded(wasExpanded) }
    }

    private fun create(start: Long, end: Long): Long {
        val id = ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        })!!)
        ids.add(id)
        return id
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (String) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000L
        var text = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.calendarItemsContainer)
                text = collectText(container)
                ready = condition(text)
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Calendar widget did not reach expected state. Text: $text")
    }

    private fun collectText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { collectText(view.getChildAt(it)) }
        else -> ""
    }
}
