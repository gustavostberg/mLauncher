package com.gustav.mlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GtdPanelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = LauncherPreferences(context)
    private val tasksUri = Uri.parse("content://com.gustav.minigtd.launcher/tasks")
    private var wasEnabled = false

    @Before fun setUp() {
        wasEnabled = preferences.loadHomeIntegrationEnabled()
        preferences.saveHomeIntegrationEnabled(true)
    }

    @After fun tearDown() {
        preferences.saveHomeIntegrationEnabled(wasEnabled)
    }

    @Test fun panelShowsTheProvidersNextTasks() {
        val expected = mutableListOf<String>()
        context.contentResolver.query(tasksUri, null, null, null, null)!!.use { cursor ->
            while (cursor.moveToNext() && expected.size < 9) {
                assertEquals("NEXT", cursor.getString(cursor.getColumnIndexOrThrow("listType")))
                expected.add(cursor.getString(cursor.getColumnIndexOrThrow("title")))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { container ->
                val lines = (0 until container.childCount).map { (container.getChildAt(it) as TextView).text.toString() }
                if (expected.isEmpty()) {
                    lines == listOf(context.getString(R.string.home_gtd_empty))
                } else {
                    lines.size == expected.size && expected.zip(lines).all { (title, line) -> title in line }
                }
            }
        }
    }

    @Test fun headingBodyAndPlusOpenTheirRespectiveScreens() {
        val startedIntent = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.packageName != "com.gustav.minigtd") return null
                startedIntent.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val routes = listOf(
                    R.id.gtdTitleView to "OPEN_INBOX",
                    R.id.gtdPanel to "OPEN_NEXT",
                    R.id.gtdAddButton to "OPEN_CAPTURE"
                )
                routes.forEach { (id, route) ->
                    startedIntent.set(null)
                    scenario.onActivity { it.findViewById<View>(id).performClick() }
                    val intent = startedIntent.get()
                    assertNotNull("No launch intent for $route", intent)
                    assertEquals("com.gustav.minigtd.MainActivity", intent.component!!.className)
                    routes.forEach { (_, candidate) ->
                        assertEquals(candidate == route, intent.getBooleanExtra("com.gustav.minigtd.extra.$candidate", false))
                    }
                    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun taskNotificationsRefreshTheOpenHomeScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { it.childCount > 0 }
            scenario.onActivity { activity ->
                // Simulate stale cached data without changing the user's tasks.
                val snapshotField = MainActivity::class.java.getDeclaredField("homeIntegrationSnapshot")
                    .apply { isAccessible = true }
                val snapshot = snapshotField.get(activity)!!
                val gtdField = snapshot.javaClass.getDeclaredField("gtd").apply { isAccessible = true }
                val emptyState = gtdField.type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                gtdField.set(snapshot, emptyState)
                MainActivity::class.java.getDeclaredMethod("renderHomeIntegrations").apply { isAccessible = true }
                    .invoke(activity)
                assertEquals(0, activity.findViewById<ViewGroup>(R.id.gtdItemsContainer).childCount)
            }
            context.contentResolver.notifyChange(tasksUri, null, 0)
            await(scenario) { it.childCount > 0 }
        }
    }

    @Test fun nineTaskRowsFitThePhonePanel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario) { it.childCount > 0 }
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.gtdItemsContainer)
                val addLine = MainActivity::class.java.declaredMethods.single {
                    it.name == "addHomePanelLine" && it.parameterCount == 5
                }.apply { isAccessible = true }
                container.removeAllViews()
                repeat(9) { addLine.invoke(activity, container, "next: Widget test ${it + 1}", false, false, null) }
                val root = activity.window.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                root.layout(root.left, root.top, root.right, root.bottom)
                val panel = activity.findViewById<View>(R.id.gtdPanel)
                assertTrue(panel.isShown)
                assertEquals(9, container.childCount)
                val lastRow = container.getChildAt(8)
                val visible = Rect()
                assertTrue(lastRow.getGlobalVisibleRect(visible))
                assertEquals("The last task row is clipped", lastRow.height, visible.height())
                assertTrue(container.top + lastRow.bottom <= panel.height)

                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "gtd-widget-test.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (ViewGroup) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it.findViewById(R.id.gtdItemsContainer)) }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("GTD panel did not reach the expected state")
    }
}
