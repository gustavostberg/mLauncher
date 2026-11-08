package com.gustav.mlauncher

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.gustav.mlauncher.data.LauncherPreferences
import com.gustav.mlauncher.model.LaunchableApp
import com.gustav.mlauncher.tesla.TeslaSyncScheduler
import com.gustav.mlauncher.tesla.TeslaRepository
import com.gustav.mlauncher.tesla.TeslaFetchResult
import com.gustav.mlauncher.tesla.TeslaSyncWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LauncherBehaviorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = LauncherPreferences(context)
    private var originalDarkMode = false
    private var originalTeslaEnabled = false

    @Before fun setUp() {
        originalDarkMode = preferences.loadDarkModeEnabled()
        originalTeslaEnabled = preferences.loadTeslaEnabled()
    }

    @After fun tearDown() {
        preferences.saveDarkModeEnabled(originalDarkMode)
        preferences.saveTeslaEnabled(originalTeslaEnabled)
        assertTrue(context.getSharedPreferences("launcher_prefs", 0).edit().commit())
    }

    @Test fun settingsCanScrollBackUpWithoutOpeningTheDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitApps(scenario)
            scenario.onActivity { openSettings(it) }
            onView(withId(R.id.settingsScrollContainer)).perform(swipeUp(), swipeDown())
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScrollContainer).visibility)
                assertEquals(context.getString(R.string.settings_title), activity.findViewById<TextView>(R.id.queryView).text)
            }
        }
    }

    @Test fun homeButtonDismissesTheDrawerMenuAndItsBlur() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitApps(scenario)
            scenario.onActivity { call(it, "handleSwipeUp") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.appListView)
                assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.contextMenuOverlay).visibility)
                val originalIntent = activity.intent
                call(activity, "onNewIntent", Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                activity.intent = originalIntent
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.homeContainer).visibility)
                assertEquals("Home must close the old app menu", View.GONE,
                    activity.findViewById<View>(R.id.contextMenuOverlay).visibility)
                assertEquals(1f, activity.findViewById<View>(R.id.browseContainer).alpha, 0f)
            }
        }
    }

    @Test fun swipingAnOpenAppMenuDoesNotChangeTheUnderlyingScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitApps(scenario)
            scenario.onActivity { call(it, "handleSwipeUp") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.appListView)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
            }
            onView(withId(R.id.contextMenuOverlay)).perform(swipeUp())
            scenario.onActivity { activity ->
                val state = field(activity, "currentState")!!
                val page = state.javaClass.getDeclaredField("page").apply { isAccessible = true }.getInt(state)
                assertEquals("The menu must block page gestures", 0, page)
            }
        }
    }

    @Test fun successfulSingleMatchLaunchImmediatelyClearsSearch() {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.component?.packageName == "com.gustav.mlauncher.test.fixture") {
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                } else null
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitApps(scenario)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("allApps").apply { isAccessible = true }
                        .set(activity, listOf(fixtureApp("Alpha"), fixtureApp("Alpine")))
                    call(activity, "handleSwipeUp")
                    call(activity, "updateSearchQuery", "al")
                    assertEquals("al", activity.findViewById<TextView>(R.id.queryView).text)
                    call(activity, "updateSearchQuery", "alpha")
                    assertEquals("A launched app must leave a cleared drawer", context.getString(R.string.search_hint),
                        activity.findViewById<TextView>(R.id.queryView).text)
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun failedLaunchKeepsTheSearchSoTheUserCanCorrectIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitApps(scenario)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredField("allApps").apply { isAccessible = true }
                    .set(activity, listOf(fixtureApp("Alpha"), fixtureApp("Alpine")))
                call(activity, "updateSearchQuery", "al")
                call(activity, "launchApp", fixtureApp("Missing"))
                assertEquals("Search", field(activity, "currentState")!!.javaClass.simpleName)
                assertEquals("al", activity.findViewById<TextView>(R.id.queryView).text)
            }
        }
    }

    @Test fun lightModeUsesDarkNavigationButtons() {
        preferences.saveDarkModeEnabled(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("Light navigation bars need dark buttons",
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightNavigationBars)
            }
        }
    }

    @Test fun darkModePreservesTheHostedWidgetAndChangesLauncherText() {
        preferences.saveDarkModeEnabled(false)
        val widgetId = preferences.loadHomeWidgetId()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitApps(scenario)
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.teslaWidgetContainer)
                val host = (0 until container.childCount).map { container.getChildAt(it) }
                    .filterIsInstance<AppWidgetHostView>().firstOrNull()
                val oldColor = activity.findViewById<TextView>(R.id.clockView).currentTextColor
                openSettings(activity)
                activity.findViewById<SwitchCompat>(R.id.settingsDarkModeSwitch).isChecked = true
                assertNotEquals(oldColor, activity.findViewById<TextView>(R.id.clockView).currentTextColor)
                assertTrue(Color.red(activity.findViewById<TextView>(R.id.clockView).currentTextColor) > 200)
                assertEquals(widgetId, preferences.loadHomeWidgetId())
                if (host != null) {
                    assertSame("A launcher theme change must preserve the hosted widget", host, container.getChildAt(0))
                }
            }
        }
    }

    @Test fun hiddenFleetIntegrationDoesNotScheduleLegacyApiSync() {
        preferences.saveTeslaEnabled(true)
        val workManager = WorkManager.getInstance(context)
        val legacyWork = PeriodicWorkRequestBuilder<TeslaSyncWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork("tesla_hourly_sync", ExistingPeriodicWorkPolicy.REPLACE, legacyWork)
            .result.get(10, TimeUnit.SECONDS)
        TeslaSyncScheduler.syncFromPreferences(context)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val work = workManager.getWorkInfosForUniqueWork("tesla_hourly_sync").get(10, TimeUnit.SECONDS)
            if (work.all { it.state.isFinished }) return
            SystemClock.sleep(100)
        }
        fail("Hidden Fleet API work must be cancelled")
    }

    @Test fun hiddenFleetIntegrationRejectsRequestsBeforeReadingCredentials() {
        preferences.saveTeslaEnabled(true)
        val result = TeslaRepository(context, preferences).fetchAndCacheBatteryStatus()
        assertEquals(TeslaFetchResult.Failure("Tesla Fleet API integration is paused."), result)
    }

    @Test fun settingsRevisionMatchesTheInstalledApp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitApps(scenario)
            scenario.onActivity { activity ->
                openSettings(activity)
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                assertEquals("revision $version", activity.findViewById<TextView>(R.id.settingsRevisionView).text)
            }
        }
    }

    private fun openSettings(activity: MainActivity) {
        call(activity, "handleSwipeUp")
        activity.findViewById<View>(R.id.settingsButton).performClick()
    }

    private fun fixtureApp(label: String) = LaunchableApp(
        label, "com.gustav.mlauncher.test.fixture", ComponentName("com.gustav.mlauncher.test.fixture", label),
    )

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun call(activity: MainActivity, name: String, vararg arguments: Any): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
            .apply { isAccessible = true }.invoke(activity, *arguments)

    private fun awaitApps(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 6_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = (field(it, "allApps") as List<*>).isNotEmpty() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Apps did not load")
    }
}
