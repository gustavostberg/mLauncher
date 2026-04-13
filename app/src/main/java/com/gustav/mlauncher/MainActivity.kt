package com.gustav.mlauncher

import android.Manifest
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gustav.mlauncher.data.AppRepository
import com.gustav.mlauncher.data.LauncherPreferences
import com.gustav.mlauncher.model.LaunchableApp
import com.gustav.mlauncher.search.AppSearch
import com.gustav.mlauncher.ui.AppListAdapter
import com.gustav.mlauncher.ui.AppFonts
import com.gustav.mlauncher.ui.BatteryIconDrawable
import com.gustav.mlauncher.tesla.TeslaFetchResult
import com.gustav.mlauncher.tesla.TeslaRepository
import com.gustav.mlauncher.tesla.TeslaSyncScheduler
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "mLauncher"
        private const val APPS_PER_PAGE = 10
        private const val MAX_HOME_FAVORITES = 9
        private const val MAX_HOME_CALENDAR_LINES = 10
        private const val MAX_HOME_GTD_ITEMS = 9
        private const val TESLA_WIDGET_HOST_ID = 1200
        private val APP_NAME_SIZE_DP = floatArrayOf(20f, 22f, 24f, 26f)
        private val HOME_FAVORITE_LIMITS = intArrayOf(11, 10, 9, 8)
        private val HOME_FAVORITE_SPACING_DP = intArrayOf(10, 12, 14, 14)
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val WEB_SHORTCUT_KEY_PREFIX = "web:"
        private const val WEB_SHORTCUT_COMPONENT_PACKAGE = "com.gustav.mlauncher.web"
        private const val MINI_CALENDAR_PACKAGE = "com.gustav.minicalendar"
        private const val EXTRA_OPEN_FOCUS_DAY = "com.gustav.minicalendar.extra.OPEN_FOCUS_DAY"
        private const val EXTRA_FOCUS_DAY_MILLIS = "com.gustav.minicalendar.extra.FOCUS_DAY_MILLIS"
        private const val EXTRA_OPEN_CAPTURE_DAY = "com.gustav.minicalendar.extra.OPEN_CAPTURE_DAY"
        private const val EXTRA_CAPTURE_ALL_DAY = "com.gustav.minicalendar.extra.CAPTURE_ALL_DAY"
        private const val MINI_GTD_PACKAGE = "com.gustav.minigtd"
        private const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"
        private const val EXTRA_OPEN_NEXT = "com.gustav.minigtd.extra.OPEN_NEXT"
        private const val EXTRA_OPEN_INBOX = "com.gustav.minigtd.extra.OPEN_INBOX"
        private const val EXTRA_OPEN_CAPTURE = "com.gustav.minigtd.extra.OPEN_CAPTURE"
        private val MINI_GTD_URI: Uri = Uri.parse("content://com.gustav.minigtd.launcher/tasks")
    }

    private data class MenuAction(
        val iconRes: Int,
        val label: String,
        val action: () -> Unit,
    )

    private sealed interface LauncherState {
        data object Home : LauncherState
        data class Drawer(val page: Int = 0) : LauncherState
        data class Search(val query: String, val page: Int = 0) : LauncherState
        data object Settings : LauncherState
    }

    private enum class HomePanelAvailability {
        LOADING,
        READY,
        PERMISSION_REQUIRED,
        APP_MISSING,
        PROVIDER_UNAVAILABLE,
    }

    private data class HomePanelState<T>(
        val availability: HomePanelAvailability = HomePanelAvailability.LOADING,
        val items: List<T> = emptyList(),
    )

    private data class HomeCalendarItem(
        val line: String,
        val isHeading: Boolean = false,
        val focusDayMillis: Long? = null,
    )

    private data class HomeTaskItem(val line: String)

    private data class HomeWidgetCandidate(
        val info: AppWidgetProviderInfo,
        val columns: Int,
        val rows: Int,
        val label: String,
        val icon: Drawable?,
    )

    private data class HomeIntegrationSnapshot(
        val calendar: HomePanelState<HomeCalendarItem> = HomePanelState(),
        val gtd: HomePanelState<HomeTaskItem> = HomePanelState(),
    )

    private data class LauncherPalette(
        val background: Int,
        val ink: Int,
        val divider: Int,
        val overlay: Int,
    )

    private val appRepository = AppRepository()
    private lateinit var launcherPreferences: LauncherPreferences
    private val appListAdapter = AppListAdapter(::launchApp, ::showDrawerContextMenu)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val appWidgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val appWidgetHost by lazy { PlainAppWidgetHost(applicationContext, TESLA_WIDGET_HOST_ID) }
    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "Uninstall result callback resultCode=${result.resultCode}")
            loadAppsAsync()
        }
    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            loadAppsAsync()
        }
    private val bindHomeWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleHomeWidgetBound(result.resultCode)
        }
    private val configureHomeWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleHomeWidgetConfigured(result.resultCode)
        }

    private lateinit var rootView: View
    private lateinit var homeContainer: View
    private lateinit var browseContainer: View
    private lateinit var homeTopRow: LinearLayout
    private lateinit var homeContentRow: LinearLayout
    private lateinit var favoritesColumn: LinearLayout
    private lateinit var integrationColumn: LinearLayout
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var batteryView: TextView
    private lateinit var teslaStatusView: TextView
    private lateinit var teslaWidgetSlot: FrameLayout
    private lateinit var teslaWidgetContainer: FrameLayout
    private lateinit var teslaWidgetPlaceholder: LinearLayout
    private lateinit var queryView: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsScrollContainer: View
    private lateinit var settingsContainer: LinearLayout
    private lateinit var settingsAppNameSizeValue: TextView
    private lateinit var settingsDarkModeSwitch: SwitchCompat
    private lateinit var settingsChromeLinkAddButton: TextView
    private lateinit var settingsIntegrationSwitch: SwitchCompat
    private lateinit var settingsIntegrationHint: TextView
    private lateinit var settingsTeslaSwitch: SwitchCompat
    private lateinit var settingsWidgetExpandedSwitch: SwitchCompat
    private lateinit var settingsWidgetDebugSwitch: SwitchCompat
    private lateinit var settingsTeslaStatus: TextView
    private lateinit var settingsTeslaConfigureButton: TextView
    private lateinit var settingsTeslaRefreshButton: TextView
    private lateinit var settingsTeslaDisconnectButton: TextView
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var calendarPanel: LinearLayout
    private lateinit var calendarItemsContainer: LinearLayout
    private lateinit var gtdPanel: LinearLayout
    private lateinit var gtdTitleView: TextView
    private lateinit var gtdAddButton: TextView
    private lateinit var gtdItemsContainer: LinearLayout
    private lateinit var gtdDivider: View
    private lateinit var appListView: RecyclerView
    private lateinit var pageIndicatorContainer: LinearLayout
    private lateinit var searchDivider: View
    private lateinit var contextMenuOverlay: FrameLayout
    private lateinit var contextMenuCard: LinearLayout
    private lateinit var contextMenuTitle: TextView
    private lateinit var contextMenuDivider: View
    private lateinit var contextMenuActionsContainer: LinearLayout
    private lateinit var contextMenuCloseButton: ImageButton

    private var allApps: List<LaunchableApp> = emptyList()
    private var favoriteApps: List<LaunchableApp> = emptyList()
    private var currentBrowseApps: List<LaunchableApp> = emptyList()
    private var homeIntegrationSnapshot = HomeIntegrationSnapshot()
    private var currentState: LauncherState = LauncherState.Home
    private var batteryReceiverRegistered = false
    private var currentBlurTarget: View? = null
    private var suppressIntegrationSwitchCallback = false
    private var pendingHomeWidgetId: Int? = null
    @Volatile
    private var appsLoading = false

    private val workSansRegular by lazy { AppFonts.regular(this) }
    private val workSansItalic by lazy { AppFonts.italic(this) }
    private val workSansMedium by lazy { AppFonts.medium(this) }
    private val workSansExtraLight by lazy { AppFonts.extraLight(this) }

    private val clockTicker = object : Runnable {
        override fun run() {
            renderClock()
            val delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 25L
            mainHandler.postDelayed(this, delay)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBattery(intent)
        }
    }

    private val gestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val startEvent = e1 ?: return false
                    val distanceY = startEvent.y - e2.y
                    val distanceX = e2.x - startEvent.x

                    if (abs(distanceY) > 110f && abs(distanceY) > abs(distanceX) && abs(velocityY) > 180f) {
                        return if (distanceY > 0f) {
                            handleSwipeUp()
                        } else {
                            handleSwipeDown()
                        }
                    }

                    return false
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherPreferences = LauncherPreferences(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        bindViews()
        applyLauncherTheme()
        setupList()
        applyAppNameTextSize()
        setupHomePanels()
        setupSettingsControls()
        setupContextMenu()
        setupBackHandling()
        setupTeslaWidgetSlot()

        renderClock()
        renderTeslaHomeStatus()
        renderTeslaWidgetSlot()
        renderState(LauncherState.Home)
        TeslaSyncScheduler.syncFromPreferences(this)
        loadAppsAsync()
    }

    override fun onStart() {
        super.onStart()
        if (!batteryReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            batteryReceiverRegistered = true
        }

        runCatching { appWidgetHost.startListening() }
        renderTeslaWidgetSlot()
        mainHandler.removeCallbacks(clockTicker)
        mainHandler.post(clockTicker)
    }

    override fun onResume() {
        super.onResume()
        TeslaSyncScheduler.syncFromPreferences(this)
        renderTeslaHomeStatus()
        loadAppsAsync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            renderState(LauncherState.Home)
        }
    }

    override fun onStop() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        runCatching { appWidgetHost.stopListening() }
        mainHandler.removeCallbacks(clockTicker)
        super.onStop()
    }

    override fun onDestroy() {
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleKeyDown(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.rootView)
        homeContainer = findViewById(R.id.homeContainer)
        browseContainer = findViewById(R.id.browseContainer)
        homeTopRow = findViewById(R.id.homeTopRow)
        homeContentRow = findViewById(R.id.homeContentRow)
        favoritesColumn = findViewById(R.id.favoritesColumn)
        integrationColumn = findViewById(R.id.integrationColumn)
        clockView = findViewById(R.id.clockView)
        dateView = findViewById(R.id.dateView)
        batteryView = findViewById(R.id.batteryView)
        teslaStatusView = findViewById(R.id.teslaStatusView)
        teslaWidgetSlot = findViewById(R.id.teslaWidgetSlot)
        teslaWidgetContainer = findViewById(R.id.teslaWidgetContainer)
        teslaWidgetPlaceholder = findViewById(R.id.teslaWidgetPlaceholder)
        queryView = findViewById(R.id.queryView)
        settingsButton = findViewById(R.id.settingsButton)
        settingsScrollContainer = findViewById(R.id.settingsScrollContainer)
        settingsContainer = findViewById(R.id.settingsContainer)
        settingsAppNameSizeValue = findViewById(R.id.settingsAppNameSizeValue)
        settingsDarkModeSwitch = findViewById(R.id.settingsDarkModeSwitch)
        settingsChromeLinkAddButton = findViewById(R.id.settingsChromeLinkAddButton)
        settingsIntegrationSwitch = findViewById(R.id.settingsIntegrationSwitch)
        settingsIntegrationHint = findViewById(R.id.settingsIntegrationHint)
        settingsTeslaSwitch = findViewById(R.id.settingsTeslaSwitch)
        settingsWidgetExpandedSwitch = findViewById(R.id.settingsWidgetExpandedSwitch)
        settingsWidgetDebugSwitch = findViewById(R.id.settingsWidgetDebugSwitch)
        settingsTeslaStatus = findViewById(R.id.settingsTeslaStatus)
        settingsTeslaConfigureButton = findViewById(R.id.settingsTeslaConfigureButton)
        settingsTeslaRefreshButton = findViewById(R.id.settingsTeslaRefreshButton)
        settingsTeslaDisconnectButton = findViewById(R.id.settingsTeslaDisconnectButton)
        favoritesContainer = findViewById(R.id.favoritesContainer)
        calendarPanel = findViewById(R.id.calendarPanel)
        calendarItemsContainer = findViewById(R.id.calendarItemsContainer)
        gtdPanel = findViewById(R.id.gtdPanel)
        gtdTitleView = findViewById(R.id.gtdTitleView)
        gtdAddButton = findViewById(R.id.gtdAddButton)
        gtdItemsContainer = findViewById(R.id.gtdItemsContainer)
        gtdDivider = findViewById(R.id.gtdDivider)
        appListView = findViewById(R.id.appListView)
        pageIndicatorContainer = findViewById(R.id.pageIndicatorContainer)
        searchDivider = findViewById(R.id.searchDivider)
        contextMenuOverlay = findViewById(R.id.contextMenuOverlay)
        contextMenuCard = findViewById(R.id.contextMenuCard)
        contextMenuTitle = findViewById(R.id.contextMenuTitle)
        contextMenuDivider = findViewById(R.id.contextMenuDivider)
        contextMenuActionsContainer = findViewById(R.id.contextMenuActionsContainer)
        contextMenuCloseButton = findViewById(R.id.contextMenuCloseButton)
    }

    private fun setupList() {
        appListView.layoutManager =
            object : LinearLayoutManager(this) {
                override fun canScrollVertically(): Boolean = false
            }
        appListView.adapter = appListAdapter
        appListView.itemAnimator = null
        appListView.setHasFixedSize(true)
    }

    private fun setupHomePanels() {
        clockView.setOnClickListener { openClockApp() }
        dateView.setOnClickListener { openCalendarHome() }
        batteryView.setOnClickListener { openSystemSettings() }
        calendarPanel.setOnClickListener { handleCalendarPanelClick() }
        gtdPanel.setOnClickListener { openMiniGtd(openCapture = false) }
        gtdTitleView.setOnClickListener { openMiniGtdInbox() }
        gtdAddButton.setOnClickListener { openMiniGtd(openCapture = true) }
        settingsButton.setOnClickListener { renderState(LauncherState.Settings) }
    }

    private fun setupTeslaWidgetSlot() {
        teslaWidgetPlaceholder.setOnClickListener { showHomeWidgetPickerDialog() }
    }

    private fun setupSettingsControls() {
        settingsAppNameSizeValue.setOnClickListener {
            val nextIndex = (launcherPreferences.loadAppNameSizeIndex() + 1) % APP_NAME_SIZE_DP.size
            launcherPreferences.saveAppNameSizeIndex(nextIndex)
            applyAppNameTextSize()
            favoriteApps = resolveFavoriteApps(allApps)
            renderFavorites()
            renderSettingsControls()
        }
        settingsChromeLinkAddButton.setOnClickListener {
            showWebShortcutDialog()
        }

        settingsDarkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressIntegrationSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            launcherPreferences.saveDarkModeEnabled(isChecked)
            applyLauncherTheme()
            renderFavorites()
            renderHomeIntegrations()
            renderState(currentState)
            updateBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }

        settingsIntegrationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressIntegrationSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            if (!isHomeIntegrationAvailable()) {
                renderSettingsControls()
                return@setOnCheckedChangeListener
            }

            launcherPreferences.saveHomeIntegrationEnabled(isChecked)
            homeIntegrationSnapshot = loadHomeIntegrationSnapshot()
            renderFavorites()
            renderHomeIntegrations()
            if (currentState == LauncherState.Settings) {
                renderSettingsControls()
            }
        }

        settingsTeslaSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressIntegrationSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            launcherPreferences.saveHomeWidgetEnabled(isChecked)

            if (!isChecked) {
                clearHomeWidgetBinding(deleteHostId = true)
                renderTeslaWidgetSlot()
                renderTeslaHomeStatus()
                renderSettingsControls()
                Toast.makeText(this, R.string.settings_widget_cleared, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            renderSettingsControls()
            renderTeslaWidgetSlot()
            showHomeWidgetPickerDialog()
        }

        settingsWidgetExpandedSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressIntegrationSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            launcherPreferences.saveHomeWidgetExpanded(isChecked)
            renderTeslaWidgetSlot()
            renderSettingsControls()
        }

        settingsWidgetDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressIntegrationSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            launcherPreferences.saveHomeWidgetDebug(isChecked)
            renderSettingsControls()
        }

        settingsTeslaConfigureButton.setOnClickListener {
            if (!launcherPreferences.loadHomeWidgetEnabled()) {
                suppressIntegrationSwitchCallback = true
                settingsTeslaSwitch.isChecked = true
                suppressIntegrationSwitchCallback = false
                launcherPreferences.saveHomeWidgetEnabled(true)
            }
            renderSettingsControls()
            showHomeWidgetPickerDialog()
        }
        settingsTeslaRefreshButton.visibility = View.GONE
        settingsTeslaDisconnectButton.setOnClickListener {
            clearHomeWidgetBinding(deleteHostId = true)
            renderTeslaWidgetSlot()
            renderTeslaHomeStatus()
            renderSettingsControls()
            Toast.makeText(this, R.string.settings_widget_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupContextMenu() {
        contextMenuOverlay.setOnClickListener { dismissContextMenu() }
        contextMenuCard.setOnClickListener { }
        contextMenuCloseButton.setOnClickListener { dismissContextMenu() }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (contextMenuOverlay.visibility == View.VISIBLE) {
                        dismissContextMenu()
                        return
                    }

                    if (currentState != LauncherState.Home) {
                        renderState(LauncherState.Home)
                    }
                }
            },
        )
    }

    private fun loadAppsAsync() {
        if (appsLoading) {
            return
        }

        appsLoading = true
        backgroundExecutor.execute {
            try {
                val launchableApps = appRepository.loadLaunchableApps(packageManager, packageName)
                val customizedApps = applyLabelOverrides(launchableApps, launcherPreferences.loadLabelOverrides())
                val sortedApps = sortAppsByLabel(customizedApps)
                val favorites = resolveFavoriteApps(sortedApps)
                val integrationSnapshot = loadHomeIntegrationSnapshot()

                runOnUiThread {
                    appsLoading = false
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    allApps = sortedApps
                    favoriteApps = favorites
                    homeIntegrationSnapshot = integrationSnapshot
                    renderTeslaHomeStatus()
                    renderTeslaWidgetSlot()
                    renderFavorites()
                    renderHomeIntegrations()
                    renderState(currentState)
                }
            } catch (_: Exception) {
                appsLoading = false
            }
        }
    }

    private fun renderFavorites() {
        applyHomeLayoutMode()
        favoritesContainer.removeAllViews()
        val useIntegration = isHomeIntegrationEnabled()
        val appNameTextSizeDp = currentAppNameTextSizeDp()
        val appNameSpacingDp = currentHomeFavoriteSpacingDp()
        val palette = currentPalette()

        favoriteApps.take(currentHomeFavoriteLimit()).forEach { app ->
            val labelView = AppCompatTextView(this).apply {
                text = app.label
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, appNameTextSizeDp)
                setTextColor(palette.ink)
                typeface = workSansRegular
                includeFontPadding = false
                letterSpacing = 0.025f
                isAllCaps = false
                gravity = if (useIntegration) Gravity.END else Gravity.CENTER_HORIZONTAL
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                setPadding(0, 0, 4.dp, appNameSpacingDp.dp)
                setOnClickListener { launchApp(app) }
                setOnLongClickListener {
                    showHomeContextMenu(app)
                    true
                }
            }

            favoritesContainer.addView(
                labelView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun renderHomeIntegrations() {
        applyHomeLayoutMode()
        if (!isHomeIntegrationEnabled()) {
            calendarItemsContainer.removeAllViews()
            gtdItemsContainer.removeAllViews()
            return
        }

        renderCalendarPanel(homeIntegrationSnapshot.calendar)
        gtdAddButton.isEnabled = isPackageLaunchable(MINI_GTD_PACKAGE)
        gtdAddButton.alpha = if (gtdAddButton.isEnabled) 1f else 0.35f

        renderHomePanel(
            container = gtdItemsContainer,
            state = homeIntegrationSnapshot.gtd,
            emptyMessageRes = R.string.home_gtd_empty,
            missingMessageRes = R.string.home_gtd_missing,
            updateMessageRes = R.string.home_gtd_update,
        ) { it.line }
    }

    private fun applyHomeLayoutMode() {
        val useIntegration = isHomeIntegrationEnabled()
        integrationColumn.visibility = if (useIntegration) View.VISIBLE else View.GONE

        (favoritesColumn.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = if (useIntegration) 13f else 1f
        }.also { favoritesColumn.layoutParams = it }

        favoritesColumn.gravity =
            if (useIntegration) {
                Gravity.TOP or Gravity.END
            } else {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }

        favoritesContainer.gravity =
            if (useIntegration) {
                Gravity.END
            } else {
                Gravity.CENTER_HORIZONTAL
            }
    }

    private fun currentAppNameTextSizeDp(): Float {
        return APP_NAME_SIZE_DP[launcherPreferences.loadAppNameSizeIndex()]
    }

    private fun currentHomeFavoriteLimit(): Int {
        return HOME_FAVORITE_LIMITS[launcherPreferences.loadAppNameSizeIndex()]
    }

    private fun currentHomeFavoriteSpacingDp(): Int {
        return HOME_FAVORITE_SPACING_DP[launcherPreferences.loadAppNameSizeIndex()]
    }

    private fun applyAppNameTextSize() {
        appListAdapter.setLabelTextSizeDp(currentAppNameTextSizeDp())
    }

    private fun applyLauncherTheme() {
        val palette = currentPalette()
        val useDarkMode = launcherPreferences.loadDarkModeEnabled()

        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !useDarkMode

        rootView.setBackgroundColor(palette.background)
        homeContainer.setBackgroundColor(palette.background)
        browseContainer.setBackgroundColor(palette.background)
        settingsScrollContainer.setBackgroundColor(palette.background)
        settingsContainer.setBackgroundColor(palette.background)
        appListView.setBackgroundColor(palette.background)
        searchDivider.setBackgroundColor(palette.divider)
        gtdDivider.setBackgroundColor(palette.divider)
        contextMenuDivider.setBackgroundColor(palette.ink)
        contextMenuOverlay.setBackgroundColor(palette.overlay)
        contextMenuCard.background = contextMenuCardBackground(palette)
        contextMenuCloseButton.background = contextMenuCloseBackground(palette)
        contextMenuCloseButton.setColorFilter(palette.ink)
        settingsButton.setColorFilter(palette.ink)
        appListAdapter.setLabelTextColor(palette.ink)

        applyLauncherThemeToView(rootView, palette)
        renderPageIndicators(totalPages = totalPages(currentBrowseApps), currentPage = currentPageIndex())
    }

    private fun currentPalette(): LauncherPalette {
        return if (launcherPreferences.loadDarkModeEnabled()) {
            LauncherPalette(
                background = ContextCompat.getColor(this, R.color.launcher_dark_background),
                ink = ContextCompat.getColor(this, R.color.launcher_dark_ink),
                divider = ContextCompat.getColor(this, R.color.launcher_dark_divider),
                overlay = ContextCompat.getColor(this, R.color.launcher_dark_overlay),
            )
        } else {
            LauncherPalette(
                background = ContextCompat.getColor(this, R.color.white),
                ink = ContextCompat.getColor(this, R.color.black),
                divider = ContextCompat.getColor(this, R.color.ink_divider),
                overlay = 0x33FFFFFF,
            )
        }
    }

    private fun applyLauncherThemeToView(view: View, palette: LauncherPalette) {
        if (view === teslaWidgetContainer || view is AppWidgetHostView) {
            return
        }

        if (view is TextView) {
            view.setTextColor(palette.ink)
            tintCompoundDrawables(view, palette.ink)
        }

        if (view is ImageButton) {
            view.setColorFilter(palette.ink)
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyLauncherThemeToView(view.getChildAt(index), palette)
            }
        }
    }

    private fun tintCompoundDrawables(textView: TextView, color: Int) {
        textView.compoundDrawablesRelative.filterNotNull().forEach { drawable ->
            drawable.mutate().setTint(color)
        }
    }

    private fun contextMenuCardBackground(palette: LauncherPalette): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.background)
            setStroke(2.dp, palette.ink)
            cornerRadius = 18.dp.toFloat()
        }

    private fun contextMenuCloseBackground(palette: LauncherPalette): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.background)
            setStroke(2.dp, palette.ink)
        }

    private fun currentPageIndex(): Int {
        return when (val state = currentState) {
            is LauncherState.Drawer -> state.page
            is LauncherState.Search -> state.page
            LauncherState.Home,
            LauncherState.Settings,
            -> 0
        }
    }

    private fun renderSettingsControls() {
        val available = isHomeIntegrationAvailable()
        val enabled = isHomeIntegrationEnabled()
        val widgetsAvailable = appWidgetManager.installedProviders.isNotEmpty()
        val widgetEnabled = launcherPreferences.loadHomeWidgetEnabled()
        val widgetExpanded = launcherPreferences.loadHomeWidgetExpanded()
        val widgetDebug = launcherPreferences.loadHomeWidgetDebug()
        val hasWidget = launcherPreferences.loadHomeWidgetId() != null
        val appNameSize = currentAppNameTextSizeDp()
        val darkModeEnabled = launcherPreferences.loadDarkModeEnabled()
        settingsAppNameSizeValue.text = getString(R.string.settings_app_name_size_value, appNameSize.toInt())
        suppressIntegrationSwitchCallback = true
        settingsDarkModeSwitch.isChecked = darkModeEnabled
        settingsIntegrationSwitch.isEnabled = available
        settingsIntegrationSwitch.isChecked = enabled
        settingsTeslaSwitch.isEnabled = widgetsAvailable
        settingsTeslaSwitch.isChecked = widgetEnabled
        settingsWidgetExpandedSwitch.isEnabled = widgetsAvailable
        settingsWidgetExpandedSwitch.isChecked = widgetExpanded
        settingsWidgetDebugSwitch.isChecked = widgetDebug
        suppressIntegrationSwitchCallback = false
        settingsIntegrationHint.text =
            getString(
                if (available) {
                    R.string.settings_integration_available
                } else {
                    R.string.settings_integration_requires_apps
                },
            )
        settingsIntegrationHint.alpha = if (available) 0.72f else 0.64f
        settingsTeslaConfigureButton.isEnabled = widgetsAvailable
        settingsTeslaConfigureButton.alpha = if (widgetsAvailable) 1f else 0.45f
        settingsTeslaRefreshButton.visibility = View.GONE
        settingsTeslaDisconnectButton.isEnabled = hasWidget
        settingsTeslaDisconnectButton.alpha = if (settingsTeslaDisconnectButton.isEnabled) 1f else 0.45f
        renderTeslaSettingsStatus()
    }

    private fun renderTeslaSettingsStatus() {
        val lines = mutableListOf<String>()
        val widgetsAvailable = appWidgetManager.installedProviders.isNotEmpty()
        val widgetEnabled = launcherPreferences.loadHomeWidgetEnabled()
        val widgetId = launcherPreferences.loadHomeWidgetId()
        val info = widgetId?.let { appWidgetManager.getAppWidgetInfo(it) }

        when {
            !widgetsAvailable -> lines += getString(R.string.settings_widget_missing)
            !widgetEnabled -> lines += getString(R.string.settings_widget_off)
            info == null -> lines += getString(R.string.settings_widget_none)
            else -> lines += getString(R.string.settings_widget_selected, info.loadLabel(packageManager))
        }

        if (launcherPreferences.loadHomeWidgetDebug()) {
            val matchingWidgets = loadHomeWidgetCandidates()
            lines += ""
            lines += getString(R.string.settings_widget_debug_matches)
            if (matchingWidgets.isEmpty()) {
                lines += getString(R.string.settings_widget_debug_none)
            } else {
                matchingWidgets.forEach { candidate ->
                    lines +=
                        getString(
                            R.string.settings_widget_debug_item,
                            candidate.label,
                            candidate.columns,
                            candidate.rows,
                        )
                }
            }

            val teslaWidgets =
                appWidgetManager.installedProviders
                    .filter { it.provider.packageName == "com.teslamotors.tesla" }
                    .map { info ->
                        val label = info.loadLabel(packageManager).toString().ifBlank { info.provider.className }
                        getString(
                            R.string.settings_widget_debug_raw_item,
                            label,
                            info.minWidth,
                            info.minHeight,
                            resolveWidgetColumns(info),
                            resolveWidgetRows(info),
                        )
                    }

            lines += ""
            lines += getString(R.string.settings_widget_debug_tesla)
            if (teslaWidgets.isEmpty()) {
                lines += getString(R.string.settings_widget_debug_none)
            } else {
                lines += teslaWidgets
            }
        }

        settingsTeslaStatus.text = lines.joinToString(separator = "\n")
        settingsTeslaConfigureButton.text =
            if (widgetId != null) getString(R.string.settings_widget_change) else getString(R.string.settings_widget_choose)
    }

    private fun renderTeslaHomeStatus() {
        teslaStatusView.visibility = View.GONE
    }

    private fun renderTeslaWidgetSlot() {
        val widgetId = launcherPreferences.loadHomeWidgetId()
        val shouldShowSlot = launcherPreferences.loadHomeWidgetEnabled()

        updateHomeWidgetSlotSize(widgetId?.let { appWidgetManager.getAppWidgetInfo(it) })
        teslaWidgetSlot.visibility = if (shouldShowSlot) View.VISIBLE else View.GONE
        if (!shouldShowSlot) {
            teslaWidgetContainer.removeAllViews()
            teslaWidgetPlaceholder.visibility = View.GONE
            teslaStatusView.visibility = View.GONE
            return
        }

        if (widgetId == null || widgetId <= 0) {
            teslaWidgetContainer.removeAllViews()
            teslaWidgetPlaceholder.visibility = View.VISIBLE
            renderTeslaHomeStatus()
            return
        }

        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        if (info == null) {
            clearHomeWidgetBinding(deleteHostId = true)
            teslaWidgetContainer.removeAllViews()
            teslaWidgetPlaceholder.visibility = View.VISIBLE
            renderTeslaHomeStatus()
            return
        }

        updateHomeWidgetOptions(widgetId)

        val hostView = appWidgetHost.createView(this, widgetId, info).apply {
            setAppWidget(widgetId, info)
            layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
        }

        teslaWidgetContainer.removeAllViews()
        teslaWidgetContainer.addView(hostView)
        teslaWidgetPlaceholder.visibility = View.GONE
        teslaStatusView.visibility = View.GONE
    }

    private fun showHomeWidgetPickerDialog() {
        val candidates = loadHomeWidgetCandidates()
        if (candidates.isEmpty()) {
            val messageRes =
                if (appWidgetManager.installedProviders.isEmpty()) {
                    R.string.settings_widget_missing
                } else {
                    R.string.settings_widget_no_matching
                }
            Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_widget_dialog_title)
            .setAdapter(HomeWidgetChoiceAdapter(candidates)) { _, which ->
                bindHomeWidget(candidates[which].info)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun bindHomeWidget(providerInfo: AppWidgetProviderInfo) {
        clearHomeWidgetBinding(deleteHostId = true)
        val widgetId = appWidgetHost.allocateAppWidgetId()
        pendingHomeWidgetId = widgetId

        val wasBound =
            runCatching {
                appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, providerInfo.provider)
            }.getOrDefault(false)

        if (wasBound) {
            continueHomeWidgetBinding(widgetId, providerInfo)
            return
        }

        val bindIntent =
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, providerInfo.provider)
            }

        try {
            bindHomeWidgetLauncher.launch(bindIntent)
        } catch (_: ActivityNotFoundException) {
            appWidgetHost.deleteAppWidgetId(widgetId)
            pendingHomeWidgetId = null
            launcherPreferences.saveHomeWidgetEnabled(false)
            renderSettingsControls()
            renderTeslaWidgetSlot()
            Toast.makeText(this, R.string.settings_widget_picker_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleHomeWidgetBound(resultCode: Int) {
        val widgetId = pendingHomeWidgetId ?: return
        if (resultCode != RESULT_OK) {
            appWidgetHost.deleteAppWidgetId(widgetId)
            pendingHomeWidgetId = null
            renderTeslaWidgetSlot()
            renderSettingsControls()
            return
        }

        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        if (info == null) {
            appWidgetHost.deleteAppWidgetId(widgetId)
            pendingHomeWidgetId = null
            Toast.makeText(this, R.string.home_widget_bind_failed, Toast.LENGTH_SHORT).show()
            renderTeslaWidgetSlot()
            renderSettingsControls()
            return
        }

        continueHomeWidgetBinding(widgetId, info)
    }

    private fun continueHomeWidgetBinding(
        widgetId: Int,
        info: AppWidgetProviderInfo,
    ) {
        if (info.configure != null) {
            val configureIntent =
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
            try {
                configureHomeWidgetLauncher.launch(configureIntent)
            } catch (_: ActivityNotFoundException) {
                appWidgetHost.deleteAppWidgetId(widgetId)
                pendingHomeWidgetId = null
                Toast.makeText(this, R.string.settings_widget_configure_failed, Toast.LENGTH_SHORT).show()
                renderTeslaWidgetSlot()
                renderSettingsControls()
            }
            return
        }

        launcherPreferences.saveHomeWidgetEnabled(true)
        launcherPreferences.saveHomeWidgetId(widgetId)
        pendingHomeWidgetId = null
        updateHomeWidgetOptions(widgetId)
        renderTeslaWidgetSlot()
        renderSettingsControls()
        Toast.makeText(this, R.string.settings_widget_saved, Toast.LENGTH_SHORT).show()
    }

    private fun handleHomeWidgetConfigured(resultCode: Int) {
        val widgetId = pendingHomeWidgetId ?: return
        if (resultCode == RESULT_OK) {
            launcherPreferences.saveHomeWidgetEnabled(true)
            launcherPreferences.saveHomeWidgetId(widgetId)
            updateHomeWidgetOptions(widgetId)
            Toast.makeText(this, R.string.settings_widget_saved, Toast.LENGTH_SHORT).show()
        } else {
            appWidgetHost.deleteAppWidgetId(widgetId)
        }
        pendingHomeWidgetId = null
        renderTeslaWidgetSlot()
        renderSettingsControls()
    }

    private fun clearHomeWidgetBinding(deleteHostId: Boolean) {
        val existingWidgetId = launcherPreferences.loadHomeWidgetId()
        if (deleteHostId && existingWidgetId != null && existingWidgetId > 0) {
            runCatching { appWidgetHost.deleteAppWidgetId(existingWidgetId) }
        }
        launcherPreferences.saveHomeWidgetId(null)
    }

    private fun loadHomeWidgetCandidates(): List<HomeWidgetCandidate> =
        appWidgetManager.installedProviders
            .mapNotNull { info ->
                val columns = resolveWidgetColumns(info)
                val rows = resolveWidgetRows(info)
                if (rows != 2 || columns !in 2..4) {
                    return@mapNotNull null
                }

                HomeWidgetCandidate(
                    info = info,
                    columns = columns,
                    rows = rows,
                    label = info.loadLabel(packageManager).toString().ifBlank { info.provider.className },
                    icon = runCatching { packageManager.getApplicationIcon(info.provider.packageName) }.getOrNull(),
                )
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }

    private inner class HomeWidgetChoiceAdapter(
        private val items: List<HomeWidgetCandidate>,
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@MainActivity)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): HomeWidgetCandidate = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_widget_choice, parent, false)
            val item = getItem(position)

            val iconView = view.findViewById<ImageView>(R.id.widgetChoiceIcon)
            val labelView = view.findViewById<TextView>(R.id.widgetChoiceLabel)
            val sizeView = view.findViewById<TextView>(R.id.widgetChoiceSize)

            iconView.setImageDrawable(item.icon)
            labelView.text = item.label
            labelView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            sizeView.text = getString(R.string.settings_widget_size_format, item.columns, item.rows)
            sizeView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))

            return view
        }
    }

    private fun resolveWidgetColumns(info: AppWidgetProviderInfo): Int =
        resolveWidgetColumnsFromDp(
            targetSpan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellWidth else 0,
            widthDp = decodeWidgetDimensionDp(info.minWidth),
        )

    private fun resolveWidgetRows(info: AppWidgetProviderInfo): Int =
        resolveWidgetRowsFromDp(
            targetSpan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellHeight else 0,
            heightDp = decodeWidgetDimensionDp(info.minHeight),
        )

    private fun resolveWidgetColumnsFromDp(targetSpan: Int, widthDp: Int): Int {
        val inferred =
            when {
                widthDp <= 0 -> 1
                widthDp <= 190 -> 2
                widthDp <= 300 -> 3
                else -> 4
            }

        return when {
            targetSpan <= 0 -> inferred
            targetSpan in 2..4 -> maxOf(targetSpan, inferred)
            else -> inferred
        }
    }

    private fun resolveWidgetRowsFromDp(targetSpan: Int, heightDp: Int): Int {
        val inferred =
            when {
                heightDp <= 0 -> 1
                heightDp <= 190 -> 2
                heightDp <= 300 -> 3
                else -> 4
            }

        return when {
            targetSpan <= 0 -> inferred
            targetSpan in 1..4 -> maxOf(targetSpan, inferred)
            else -> inferred
        }
    }

    private fun decodeWidgetDimensionDp(value: Int): Int {
        if (value <= 0) {
            return 0
        }

        // Some providers report plain dp-sized integers here, while others use
        // Android's complex dimension encoding. Treat normal-looking values as-is.
        if (value in 1..4096) {
            return value
        }

        val decodedPx =
            runCatching { TypedValue.complexToDimensionPixelSize(value, resources.displayMetrics) }
                .getOrNull()

        if (decodedPx != null && decodedPx > 0) {
            return (decodedPx / resources.displayMetrics.density).toInt()
        }

        return value
    }

    private fun updateHomeWidgetSlotSize(info: AppWidgetProviderInfo?) {
        val expanded = launcherPreferences.loadHomeWidgetExpanded() && launcherPreferences.loadHomeWidgetEnabled()
        val columns = info?.let(::resolveWidgetColumns)?.coerceIn(2, 4) ?: 2
        val cellSizePx = resources.getDimensionPixelSize(R.dimen.home_widget_cell_size)
        val heightPx =
            resources.getDimensionPixelSize(
                if (expanded) {
                    R.dimen.home_widget_height_expanded
                } else {
                    R.dimen.home_widget_height
                },
            )
        teslaWidgetSlot.layoutParams =
            (teslaWidgetSlot.layoutParams as LinearLayout.LayoutParams).apply {
                width = cellSizePx * columns
                height = heightPx
            }

        val topPadding =
            resources.getDimensionPixelSize(
                if (expanded) {
                    R.dimen.home_top_padding_expanded_widget
                } else {
                    R.dimen.home_top_padding_default
                },
            )
        homeContainer.setPadding(
            homeContainer.paddingLeft,
            topPadding,
            homeContainer.paddingRight,
            homeContainer.paddingBottom,
        )

        clockView.layoutParams =
            (clockView.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin =
                    resources.getDimensionPixelSize(
                        if (expanded) {
                            R.dimen.home_clock_margin_top_expanded
                        } else {
                            R.dimen.home_clock_margin_top_default
                        },
                    )
            }

        homeContentRow.layoutParams =
            (homeContentRow.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin =
                    resources.getDimensionPixelSize(
                        if (expanded) {
                            R.dimen.home_content_margin_top_expanded
                        } else {
                            R.dimen.home_content_margin_top_default
                        },
                    )
            }
    }

    private fun updateHomeWidgetOptions(widgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        val slotWidthDp =
            teslaWidgetSlot.layoutParams.width / resources.displayMetrics.density
        val slotHeightDp =
            (if (info != null) resources.getDimension(R.dimen.home_widget_height) else resources.getDimension(R.dimen.tesla_widget_size)) /
                resources.displayMetrics.density
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, slotWidthDp.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, slotWidthDp.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, slotHeightDp.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, slotHeightDp.toInt())
            }
        runCatching { appWidgetManager.updateAppWidgetOptions(widgetId, options) }
    }

    private class PlainAppWidgetHost(
        context: Context,
        hostId: Int,
    ) : android.appwidget.AppWidgetHost(context, hostId) {
        private val hostContext = context.applicationContext

        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo,
        ): AppWidgetHostView {
            return AppWidgetHostView(hostContext)
        }
    }

    private fun renderCalendarPanel(state: HomePanelState<HomeCalendarItem>) {
        calendarItemsContainer.removeAllViews()

        when (state.availability) {
            HomePanelAvailability.LOADING -> Unit
            HomePanelAvailability.PERMISSION_REQUIRED -> {
                addHomePanelLine(calendarItemsContainer, getString(R.string.home_calendar_permission), isMessage = true)
            }

            HomePanelAvailability.READY -> {
                if (state.items.isEmpty()) {
                    addHomePanelLine(calendarItemsContainer, getString(R.string.home_calendar_empty), isMessage = true)
                } else {
                    state.items.forEach { item ->
                        if (item.isHeading && item.focusDayMillis != null) {
                            addCalendarHeadingLine(
                                container = calendarItemsContainer,
                                item = item,
                            )
                        } else {
                            val focusDayMillis = item.focusDayMillis
                            addHomePanelLine(
                                container = calendarItemsContainer,
                                text = item.line,
                                isMessage = false,
                                isHeading = item.isHeading,
                                onClick =
                                    if (focusDayMillis != null) {
                                        { openCalendarDay(focusDayMillis) }
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                }
            }

            HomePanelAvailability.APP_MISSING,
            HomePanelAvailability.PROVIDER_UNAVAILABLE,
            -> {
                addHomePanelLine(calendarItemsContainer, getString(R.string.home_calendar_empty), isMessage = true)
            }
        }
    }

    private fun addCalendarHeadingLine(
        container: LinearLayout,
        item: HomeCalendarItem,
    ) {
        val focusDayMillis = item.focusDayMillis ?: return
        val palette = currentPalette()

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val headingView =
            AppCompatTextView(this).apply {
                text = item.line
                textSize = 12f
                setTextColor(palette.ink)
                typeface = workSansMedium
                includeFontPadding = false
                letterSpacing = 0.02f
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                isClickable = true
                isFocusable = true
                setOnClickListener { openCalendarDay(focusDayMillis) }
            }

        row.addView(
            headingView,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                marginEnd = 10.dp
            },
        )

        val addButton =
            AppCompatTextView(this).apply {
                text = getString(R.string.home_calendar_add)
                textSize = 18f
                setTextColor(palette.ink)
                typeface = workSansRegular
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                includeFontPadding = false
                gravity = Gravity.CENTER
                minWidth = 36.dp
                minHeight = 24.dp
                setPadding(8.dp, 0, 4.dp, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { createCalendarEventForDay(focusDayMillis) }
            }

        row.addView(
            addButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 4.dp
            },
        )
    }

    private fun <T> renderHomePanel(
        container: LinearLayout,
        state: HomePanelState<T>,
        emptyMessageRes: Int,
        missingMessageRes: Int,
        updateMessageRes: Int,
        lineSelector: (T) -> String,
    ) {
        container.removeAllViews()

        when (state.availability) {
            HomePanelAvailability.LOADING -> Unit
            HomePanelAvailability.PERMISSION_REQUIRED -> Unit
            HomePanelAvailability.APP_MISSING -> {
                addHomePanelLine(container, getString(missingMessageRes), isMessage = true)
            }

            HomePanelAvailability.PROVIDER_UNAVAILABLE -> {
                addHomePanelLine(container, getString(updateMessageRes), isMessage = true)
            }

            HomePanelAvailability.READY -> {
                if (state.items.isEmpty()) {
                    addHomePanelLine(container, getString(emptyMessageRes), isMessage = true)
                } else {
                    state.items.forEach { item ->
                        addHomePanelLine(container, lineSelector(item), isMessage = false)
                    }
                }
            }
        }
    }

    private fun addHomePanelLine(
        container: LinearLayout,
        text: String,
        isMessage: Boolean,
        isHeading: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        val textView =
            AppCompatTextView(this).apply {
                val palette = currentPalette()
                this.text = text
                textSize =
                    when {
                        isMessage -> 12f
                        isHeading -> 12f
                        else -> 11f
                    }
                setTextColor(palette.ink)
                typeface = if (isHeading) workSansMedium else workSansRegular
                includeFontPadding = false
                letterSpacing = 0.02f
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
                alpha = if (isMessage) 0.62f else 1f
                if (onClick != null) {
                    background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }

        container.addView(
            textView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin =
                    when {
                        isMessage -> 0
                        isHeading -> 4
                        else -> 2
                    }
            },
        )
    }

    private fun loadHomeIntegrationSnapshot(): HomeIntegrationSnapshot {
        if (!isHomeIntegrationEnabled()) {
            return HomeIntegrationSnapshot()
        }

        return HomeIntegrationSnapshot(
            calendar = loadSystemCalendarPanelState(),
            gtd = loadGtdPanelState(),
        )
    }

    private fun loadSystemCalendarPanelState(): HomePanelState<HomeCalendarItem> {
        if (!hasCalendarReadPermission()) {
            return HomePanelState(availability = HomePanelAvailability.PERMISSION_REQUIRED)
        }

        return try {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val nowMillis = System.currentTimeMillis()
            val startMillis = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val horizonDays = 14L
            val endMillis = today.plusDays(horizonDays).plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)

            val cursor =
                contentResolver.query(
                    builder.build(),
                    arrayOf(
                        CalendarContract.Instances.EVENT_ID,
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.ALL_DAY,
                    ),
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                ) ?: return HomePanelState(availability = HomePanelAvailability.READY)

            cursor.use {
                val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val startIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)

                val events = mutableListOf<SystemCalendarEvent>()
                while (it.moveToNext()) {
                    val title = it.getString(titleIndex)?.trim().orEmpty()
                    if (title.isBlank()) {
                        continue
                    }
                    events +=
                        SystemCalendarEvent(
                            title = title,
                            startMillis = it.getLong(startIndex),
                            endMillis = it.getLong(endIndex),
                            isAllDay = it.getInt(allDayIndex) == 1,
                        )
                }

                val visibleEvents =
                    events.filter { event ->
                        event.isAllDay || event.endMillis >= nowMillis - 60 * 60 * 1000L
                    }

                HomePanelState(
                    availability = HomePanelAvailability.READY,
                    items = buildCalendarPanelLines(visibleEvents, today),
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to load system calendar integration", exception)
            HomePanelState(availability = HomePanelAvailability.PERMISSION_REQUIRED)
        }
    }

    private fun loadGtdPanelState(): HomePanelState<HomeTaskItem> {
        if (!isPackageLaunchable(MINI_GTD_PACKAGE)) {
            return HomePanelState(availability = HomePanelAvailability.APP_MISSING)
        }

        return try {
            val cursor =
                contentResolver.query(
                    MINI_GTD_URI,
                    null,
                    null,
                    null,
                    null,
                ) ?: return HomePanelState(availability = HomePanelAvailability.PROVIDER_UNAVAILABLE)

            cursor.use {
                val titleIndex = it.getColumnIndexOrThrow("title")
                val listTypeIndex = it.getColumnIndexOrThrow("listType")
                val projectNameIndex = it.getColumnIndexOrThrow("projectName")
                val dueMillisIndex = it.getColumnIndexOrThrow("dueMillis")
                val items = mutableListOf<HomeTaskItem>()

                while (it.moveToNext() && items.size < MAX_HOME_GTD_ITEMS) {
                    val dueMillis = if (it.isNull(dueMillisIndex)) null else it.getLong(dueMillisIndex)
                    items +=
                        HomeTaskItem(
                            line =
                                formatTaskLine(
                                    title = it.getString(titleIndex).orEmpty(),
                                    listType = it.getString(listTypeIndex).orEmpty(),
                                    projectName = it.getString(projectNameIndex),
                                    dueMillis = dueMillis,
                                ),
                        )
                }

                HomePanelState(
                    availability = HomePanelAvailability.READY,
                    items = items,
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to load miniGTD integration", exception)
            HomePanelState(availability = HomePanelAvailability.PROVIDER_UNAVAILABLE)
        }
    }

    private data class SystemCalendarEvent(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val isAllDay: Boolean,
    )

    private fun buildCalendarPanelLines(
        events: List<SystemCalendarEvent>,
        startDay: LocalDate,
    ): List<HomeCalendarItem> {
        val lines = mutableListOf<HomeCalendarItem>()
        val zoneId = ZoneId.systemDefault()
        var day = startDay
        var addedAnyEvent = false

        repeat(14) {
            if (lines.size >= MAX_HOME_CALENDAR_LINES) {
                return@repeat
            }

            val dayEvents =
                events
                    .filter { it.occursOnDay(day, zoneId) }
                    .sortedWith(
                        compareBy<SystemCalendarEvent>(
                            { !it.isAllDay },
                            { if (it.isAllDay) 0L else it.startMillis },
                            { it.title.lowercase(Locale.getDefault()) },
                        ),
                    )

            if (day == startDay || dayEvents.isNotEmpty()) {
                lines +=
                    HomeCalendarItem(
                        line = formatCalendarHeading(day),
                        isHeading = true,
                        focusDayMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    )

                val remaining = MAX_HOME_CALENDAR_LINES - lines.size
                if (remaining <= 0) {
                    return lines.take(MAX_HOME_CALENDAR_LINES)
                }

                dayEvents.take(remaining).forEach { event ->
                    lines +=
                        HomeCalendarItem(
                            line = formatCalendarEventLine(event),
                            isHeading = false,
                            focusDayMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        )
                    addedAnyEvent = true
                }
            }

            day = day.plusDays(1)
        }

        if (!addedAnyEvent) {
            lines += HomeCalendarItem(line = getString(R.string.home_calendar_empty))
        }

        return lines.take(MAX_HOME_CALENDAR_LINES)
    }

    private fun formatCalendarHeading(day: LocalDate): String {
        val locale = Locale.getDefault()
        return day
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d", locale))
            .lowercase(locale)
    }

    private fun formatCalendarEventLine(event: SystemCalendarEvent): String {
        if (event.isAllDay) {
            return event.title
        }

        val locale = Locale.getDefault()
        val time =
            Instant.ofEpochMilli(event.startMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", locale))
        return "$time  ${event.title}"
    }

    private fun SystemCalendarEvent.occursOnDay(day: LocalDate, zoneId: ZoneId): Boolean {
        return if (isAllDay) {
            val startDate = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val endDateExclusive = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).toLocalDate()
            !day.isBefore(startDate) && day.isBefore(endDateExclusive)
        } else {
            val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            startMillis < dayEnd && endMillis > dayStart
        }
    }

    private fun formatTaskLine(
        title: String,
        listType: String,
        projectName: String?,
        dueMillis: Long?,
    ): String {
        val locale = Locale.getDefault()
        val listPrefix =
            when (listType) {
                "NEXT" -> "next"
                "INBOX" -> "inbox"
                "WAITING" -> "waiting"
                "SOMEDAY" -> "someday"
                "PROJECT" -> projectName?.trim().orEmpty().ifBlank { "project" }
                else -> listType.lowercase(locale)
            }

        val duePrefix =
            dueMillis?.let {
                Instant.ofEpochMilli(it)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d/M", locale))
            }

        return listOfNotNull(duePrefix, "$listPrefix: $title").joinToString("  ")
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun isHomeIntegrationAvailable(): Boolean {
        return isPackageInstalled(MINI_CALENDAR_PACKAGE) && isPackageInstalled(MINI_GTD_PACKAGE)
    }

    private fun isHomeIntegrationEnabled(): Boolean {
        return isHomeIntegrationAvailable() && launcherPreferences.loadHomeIntegrationEnabled()
    }

    private fun isPackageLaunchable(packageName: String): Boolean {
        return packageManager.getLaunchIntentForPackage(packageName) != null
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun renderState(state: LauncherState) {
        when (state) {
            LauncherState.Home -> {
                favoriteApps = resolveFavoriteApps(allApps)
                renderFavorites()
                renderHomeIntegrations()
                currentState = LauncherState.Home
                homeContainer.visibility = View.VISIBLE
                browseContainer.visibility = View.GONE
                currentBrowseApps = emptyList()
            }

            is LauncherState.Drawer -> {
                homeContainer.visibility = View.GONE
                browseContainer.visibility = View.VISIBLE
                settingsScrollContainer.visibility = View.GONE
                appListView.visibility = View.VISIBLE
                renderQuery(text = getString(R.string.search_hint), isSubtle = true, showSearchIcon = true, showSettings = true)
                currentBrowseApps = allApps
                val visiblePage = renderPagedApps(
                    items = allApps,
                    requestedPage = state.page,
                    highlightFirstItem = false,
                )
                currentState = LauncherState.Drawer(visiblePage)
            }

            is LauncherState.Search -> {
                homeContainer.visibility = View.GONE
                browseContainer.visibility = View.VISIBLE
                settingsScrollContainer.visibility = View.GONE
                appListView.visibility = View.VISIBLE
                renderQuery(text = state.query, isSubtle = false, showSearchIcon = true, showSettings = true)
                currentBrowseApps = AppSearch.filter(allApps, state.query)
                val visiblePage = renderPagedApps(
                    items = currentBrowseApps,
                    requestedPage = state.page,
                    highlightFirstItem = currentBrowseApps.isNotEmpty(),
                )
                currentState = LauncherState.Search(state.query, visiblePage)
            }

            LauncherState.Settings -> {
                homeContainer.visibility = View.GONE
                browseContainer.visibility = View.VISIBLE
                currentBrowseApps = emptyList()
                settingsScrollContainer.visibility = View.VISIBLE
                appListView.visibility = View.GONE
                renderSettingsControls()
                renderPageIndicators(totalPages = 0, currentPage = 0)
                renderQuery(
                    text = getString(R.string.settings_title),
                    isSubtle = false,
                    showSearchIcon = false,
                    showSettings = false,
                )
                currentState = LauncherState.Settings
            }
        }
    }

    private fun renderQuery(
        text: String,
        isSubtle: Boolean,
        showSearchIcon: Boolean,
        showSettings: Boolean,
    ) {
        queryView.text = text
        queryView.typeface = if (isSubtle) workSansItalic else workSansRegular
        queryView.alpha = 1f
        queryView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (showSearchIcon) R.drawable.ic_search_minimal else 0,
            0,
            0,
            0,
        )
        tintCompoundDrawables(queryView, currentPalette().ink)
        settingsButton.visibility = if (showSettings) View.VISIBLE else View.GONE
    }

    private fun renderPagedApps(
        items: List<LaunchableApp>,
        requestedPage: Int,
        highlightFirstItem: Boolean,
    ): Int {
        if (items.isEmpty()) {
            appListAdapter.submitList(emptyList(), highlightFirstItem = false)
            renderPageIndicators(totalPages = 0, currentPage = 0)
            return 0
        }

        val totalPages = totalPages(items)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val startIndex = page * APPS_PER_PAGE
        val endIndex = (startIndex + APPS_PER_PAGE).coerceAtMost(items.size)
        val pageItems = items.subList(startIndex, endIndex)

        appListAdapter.submitList(
            pageItems,
            highlightFirstItem = highlightFirstItem && page == 0 && pageItems.isNotEmpty(),
        )
        renderPageIndicators(totalPages = totalPages, currentPage = page)
        return page
    }

    private fun renderPageIndicators(totalPages: Int, currentPage: Int) {
        pageIndicatorContainer.removeAllViews()
        pageIndicatorContainer.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        if (totalPages <= 1) {
            return
        }

        val indicatorWidth = resources.getDimensionPixelSize(R.dimen.page_indicator_width)
        val indicatorHeight = resources.getDimensionPixelSize(R.dimen.page_indicator_height)
        val activeIndicatorHeight = resources.getDimensionPixelSize(R.dimen.page_indicator_active_height)
        val indicatorSpacing = resources.getDimensionPixelSize(R.dimen.page_indicator_spacing)
        val inactiveStrokeWidth = resources.getDimensionPixelSize(R.dimen.page_indicator_stroke)
        val palette = currentPalette()

        repeat(totalPages) { index ->
            val isActive = index == currentPage
            val indicatorView =
                View(this).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            if (isActive) {
                                setColor(palette.ink)
                            } else {
                                setColor(ContextCompat.getColor(context, android.R.color.transparent))
                                setStroke(inactiveStrokeWidth, palette.ink)
                            }
                        }
                }

            pageIndicatorContainer.addView(
                indicatorView,
                LinearLayout.LayoutParams(
                    if (isActive) activeIndicatorHeight else indicatorWidth,
                    if (isActive) activeIndicatorHeight else indicatorHeight,
                ).apply {
                    if (index > 0) {
                        topMargin = indicatorSpacing
                    }
                },
            )
        }
    }

    private fun renderClock() {
        val now = ZonedDateTime.now()
        val locale = Locale.getDefault()
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)

        clockView.text = now.format(timeFormatter)
        dateView.text = now.format(dateFormatter).lowercase(locale)
    }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            return
        }

        val percent = (level * 100) / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

        val palette = currentPalette()
        batteryView.text = getString(R.string.battery_format, percent)
        batteryView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null,
            null,
            BatteryIconDrawable(this, percent, isCharging, palette.ink, palette.background),
            null,
        )
    }

    private fun updateSearchQuery(query: String) {
        if (query.isBlank()) {
            renderState(LauncherState.Drawer())
        } else {
            val results = AppSearch.filter(allApps, query)
            if (results.size == 1) {
                launchApp(results.first())
            } else {
                renderState(LauncherState.Search(query))
            }
        }
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        if (contextMenuOverlay.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                dismissContextMenu()
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                if (currentState is LauncherState.Search) {
                    currentBrowseApps.firstOrNull()?.let(::launchApp)
                    return true
                }
            }

            KeyEvent.KEYCODE_DEL -> {
                val searchState = currentState as? LauncherState.Search ?: return false
                updateSearchQuery(searchState.query.dropLast(1))
                return true
            }
        }

        val typedCharacter = event.unicodeChar.takeIf { it != 0 }?.toChar()
        if (typedCharacter != null && !typedCharacter.isISOControl()) {
            val nextQuery = when (val state = currentState) {
                LauncherState.Home,
                is LauncherState.Drawer,
                LauncherState.Settings,
                -> typedCharacter.toString()

                is LauncherState.Search -> state.query + typedCharacter
            }
            updateSearchQuery(nextQuery)
            return true
        }

        return false
    }

    private fun handleSwipeUp(): Boolean {
        return when (val state = currentState) {
            LauncherState.Home -> {
                renderState(LauncherState.Drawer())
                true
            }

            is LauncherState.Drawer -> {
                if (hasNextPage(allApps, state.page)) {
                    renderState(state.copy(page = state.page + 1))
                    true
                } else {
                    false
                }
            }

            is LauncherState.Search -> {
                if (hasNextPage(currentBrowseApps, state.page)) {
                    renderState(state.copy(page = state.page + 1))
                    true
                } else {
                    false
                }
            }

            LauncherState.Settings -> false
        }
    }

    private fun handleSwipeDown(): Boolean {
        return when (val state = currentState) {
            LauncherState.Home -> false

            is LauncherState.Drawer -> {
                if (state.page > 0) {
                    renderState(state.copy(page = state.page - 1))
                } else {
                    renderState(LauncherState.Home)
                }
                true
            }

            is LauncherState.Search -> {
                if (state.page > 0) {
                    renderState(state.copy(page = state.page - 1))
                } else {
                    renderState(LauncherState.Drawer())
                }
                true
            }

            LauncherState.Settings -> {
                renderState(LauncherState.Drawer())
                true
            }
        }
    }

    private fun hasNextPage(items: List<LaunchableApp>, currentPage: Int): Boolean {
        return currentPage < totalPages(items) - 1
    }

    private fun totalPages(items: List<LaunchableApp>): Int {
        return if (items.isEmpty()) 0 else (items.size + APPS_PER_PAGE - 1) / APPS_PER_PAGE
    }

    private fun launchApp(app: LaunchableApp) {
        if (currentState is LauncherState.Search) {
            currentState = LauncherState.Drawer()
            currentBrowseApps = allApps
        }

        try {
            startActivity(app.buildLaunchIntent())
        } catch (_: ActivityNotFoundException) {
            if (app.isWebShortcut && !app.webUrl.isNullOrBlank()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: ActivityNotFoundException) {
                    // Fall through to the shared failure toast.
                }
            }
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCalendarPanelClick() {
        if (!hasCalendarReadPermission()) {
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            return
        }

        openCalendarDay(
            LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        )
    }

    private fun hasCalendarReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openClockApp() {
        val intents =
            listOf(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                Intent(AlarmClock.ACTION_SET_ALARM),
                packageManager.getLaunchIntentForPackage("com.google.android.deskclock"),
                packageManager.getLaunchIntentForPackage("com.android.deskclock"),
            ).filterNotNull()

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next fallback.
            }
        }

        Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemCalendar(focusDayMillis: Long) {
        val calendarIntent =
            Intent(Intent.ACTION_VIEW).apply {
                data =
                    CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { builder ->
                        ContentUris.appendId(builder, focusDayMillis)
                    }.build()
            }

        try {
            startActivity(calendarIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCalendarHome() {
        if (openMiniCalendarHome()) {
            return
        }

        val googleCalendarIntent = packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR_PACKAGE)
        if (googleCalendarIntent != null) {
            try {
                startActivity(googleCalendarIntent)
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through to generic calendar view.
            }
        }

        openSystemCalendar(
            LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        )
    }

    private fun openCalendarDay(focusDayMillis: Long) {
        if (!openMiniCalendarFocus(focusDayMillis)) {
            openSystemCalendar(focusDayMillis)
        }
    }

    private fun createCalendarEventForDay(focusDayMillis: Long) {
        if (openMiniCalendarCapture(focusDayMillis, isAllDay = false)) {
            return
        }

        val insertIntent =
            Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, focusDayMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, focusDayMillis + 60 * 60 * 1000L)
            }

        try {
            startActivity(insertIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMiniCalendarFocus(focusDayMillis: Long): Boolean {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(MINI_CALENDAR_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(MINI_CALENDAR_PACKAGE, "com.gustav.minicalendar.MainActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
        return try {
            launchIntent.putExtra(EXTRA_OPEN_FOCUS_DAY, true)
            launchIntent.putExtra(EXTRA_FOCUS_DAY_MILLIS, focusDayMillis)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun openMiniCalendarHome(): Boolean {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(MINI_CALENDAR_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(MINI_CALENDAR_PACKAGE, "com.gustav.minicalendar.MainActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun openMiniCalendarCapture(
        focusDayMillis: Long,
        isAllDay: Boolean,
    ): Boolean {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(MINI_CALENDAR_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(MINI_CALENDAR_PACKAGE, "com.gustav.minicalendar.MainActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

        return try {
            launchIntent.putExtra(EXTRA_OPEN_CAPTURE_DAY, true)
            launchIntent.putExtra(EXTRA_FOCUS_DAY_MILLIS, focusDayMillis)
            launchIntent.putExtra(EXTRA_CAPTURE_ALL_DAY, isAllDay)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun launchPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(launchIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMiniGtd(openCapture: Boolean) {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(MINI_GTD_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(MINI_GTD_PACKAGE, "com.gustav.minigtd.MainActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

        launchIntent.putExtra(EXTRA_OPEN_NEXT, true)
        if (openCapture) {
            launchIntent.putExtra(EXTRA_OPEN_CAPTURE, true)
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        try {
            startActivity(launchIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMiniGtdInbox() {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(MINI_GTD_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(MINI_GTD_PACKAGE, "com.gustav.minigtd.MainActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

        launchIntent.putExtra(EXTRA_OPEN_INBOX, true)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        try {
            startActivity(launchIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHomeContextMenu(app: LaunchableApp) {
        if (app.isWebShortcut) {
            showContextMenu(
                app = app,
                actions = listOf(
                    MenuAction(R.drawable.ic_context_edit, getString(R.string.action_edit)) {
                        showWebShortcutDialog(app)
                    },
                    MenuAction(R.drawable.ic_context_remove, getString(R.string.action_remove)) {
                        removeFavorite(app)
                    },
                ),
            )
            return
        }

        showContextMenu(
            app = app,
            actions = listOf(
                MenuAction(R.drawable.ic_context_info, getString(R.string.action_app_info)) {
                    openAppInfo(app)
                },
                MenuAction(R.drawable.ic_context_remove, getString(R.string.action_remove)) {
                    removeFavorite(app)
                },
            ),
        )
    }

    private fun showDrawerContextMenu(app: LaunchableApp) {
        showContextMenu(
            app = app,
            actions = listOf(
                MenuAction(R.drawable.ic_context_info, getString(R.string.action_app_info)) {
                    openAppInfo(app)
                },
                MenuAction(R.drawable.ic_context_add, getString(R.string.action_add)) {
                    addFavorite(app)
                },
                MenuAction(R.drawable.ic_context_edit, getString(R.string.action_edit)) {
                    editAppLabel(app)
                },
                MenuAction(R.drawable.ic_context_share, getString(R.string.action_share)) {
                    shareApp(app)
                },
                MenuAction(R.drawable.ic_context_uninstall, getString(R.string.action_uninstall)) {
                    uninstallApp(app)
                },
            ),
        )
    }

    private fun showContextMenu(app: LaunchableApp, actions: List<MenuAction>) {
        val palette = currentPalette()
        contextMenuTitle.text = app.label
        contextMenuTitle.setTextColor(palette.ink)
        contextMenuDivider.setBackgroundColor(palette.ink)
        contextMenuCard.background = contextMenuCardBackground(palette)
        contextMenuCloseButton.background = contextMenuCloseBackground(palette)
        contextMenuCloseButton.setColorFilter(palette.ink)
        contextMenuActionsContainer.removeAllViews()

        actions.forEach { menuAction ->
            val actionView =
                layoutInflater.inflate(R.layout.item_context_menu_action, contextMenuActionsContainer, false)
            val iconView = actionView.findViewById<android.widget.ImageView>(R.id.contextActionIcon)
            val labelView = actionView.findViewById<TextView>(R.id.contextActionLabel)

            iconView.setImageResource(menuAction.iconRes)
            iconView.setColorFilter(palette.ink)
            labelView.text = menuAction.label
            labelView.setTextColor(palette.ink)
            actionView.setOnClickListener {
                dismissContextMenu()
                menuAction.action()
            }

            contextMenuActionsContainer.addView(actionView)
        }

        applyBlurToCurrentContent()
        contextMenuOverlay.visibility = View.VISIBLE
    }

    private fun dismissContextMenu() {
        removeBlurFromCurrentContent()
        contextMenuOverlay.visibility = View.GONE
        contextMenuActionsContainer.removeAllViews()
    }

    private fun applyBlurToCurrentContent() {
        val target =
            when (currentState) {
                LauncherState.Home -> homeContainer
                is LauncherState.Drawer,
                is LauncherState.Search,
                LauncherState.Settings,
                -> browseContainer
            }

        currentBlurTarget = target
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            target.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
        }
        target.alpha = 0.82f
    }

    private fun removeBlurFromCurrentContent() {
        currentBlurTarget?.let { target ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.setRenderEffect(null)
            }
            target.alpha = 1f
        }
        currentBlurTarget = null
    }

    private fun addFavorite(app: LaunchableApp) {
        if (favoriteApps.any { componentKey(it) == componentKey(app) }) {
            Toast.makeText(this, R.string.favorite_exists, Toast.LENGTH_SHORT).show()
            return
        }

        val favoriteLimit = currentHomeFavoriteLimit()
        if (favoriteApps.size >= favoriteLimit) {
            Toast.makeText(this, getString(R.string.favorite_limit_reached, favoriteLimit), Toast.LENGTH_SHORT).show()
            return
        }

        saveCustomFavorites(favoriteApps + app)
        refreshDisplayedApps()
    }

    private fun removeFavorite(app: LaunchableApp) {
        saveCustomFavorites(favoriteApps.filterNot { componentKey(it) == componentKey(app) })
        app.webShortcutId?.let(launcherPreferences::removeWebShortcut)
        refreshDisplayedApps()
    }

    private fun showWebShortcutDialog(existingApp: LaunchableApp? = null) {
        val existingShortcutId = existingApp?.webShortcutId
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp, 12.dp, 20.dp, 0)
            }
        val labelInput =
            EditText(this).apply {
                hint = getString(R.string.web_shortcut_name_label)
                setText(existingApp?.label.orEmpty())
                setSingleLine()
                typeface = workSansRegular
                textSize = 18f
            }
        val urlInput =
            EditText(this).apply {
                hint = getString(R.string.web_shortcut_url_label)
                setText(existingApp?.webUrl.orEmpty())
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                typeface = workSansRegular
                textSize = 18f
            }
        container.addView(labelInput)
        container.addView(urlInput)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    getString(
                        if (existingShortcutId == null) {
                            R.string.web_shortcut_add_title
                        } else {
                            R.string.web_shortcut_edit_title
                        },
                    ),
                )
                .setView(container)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = labelInput.text?.toString()?.trim().orEmpty()
            val url = normalizeWebShortcutUrl(urlInput.text?.toString().orEmpty())
            if (label.isBlank() || url == null) {
                Toast.makeText(this, R.string.web_shortcut_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (existingShortcutId == null && favoriteApps.size >= currentHomeFavoriteLimit()) {
                Toast.makeText(
                    this,
                    getString(R.string.favorite_limit_reached, currentHomeFavoriteLimit()),
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }

            val shortcut =
                LauncherPreferences.WebShortcut(
                    id = existingShortcutId ?: UUID.randomUUID().toString(),
                    label = label,
                    url = url,
                )
            launcherPreferences.saveWebShortcut(shortcut)
            val shortcutApp = shortcut.toLaunchableApp()
            if (existingShortcutId == null) {
                addFavorite(shortcutApp)
            } else {
                refreshDisplayedApps()
            }
            dialog.dismiss()
        }
    }

    private fun normalizeWebShortcutUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val withScheme =
            if (trimmed.contains("://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        val uri = Uri.parse(withScheme)
        return if ((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) {
            uri.toString()
        } else {
            null
        }
    }

    private fun editAppLabel(app: LaunchableApp) {
        val input =
            EditText(this).apply {
                setText(app.label)
                setSelection(text.length)
                setSingleLine()
                typeface = workSansRegular
                textSize = 20f
            }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_edit))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val updatedLabel = input.text?.toString()?.trim().orEmpty()
                val overrideLabel =
                    updatedLabel.takeIf { it.isNotBlank() && it != app.defaultLabel }
                launcherPreferences.saveLabelOverride(componentKey(app), overrideLabel)
                refreshDisplayedApps()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTeslaSetupDialog() {
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp, 12.dp, 20.dp, 0)
            }

        val tokenInput =
            EditText(this).apply {
                hint = getString(R.string.settings_tesla_token_label)
                setSingleLine()
                typeface = workSansRegular
                textSize = 16f
            }
        val vinInput =
            EditText(this).apply {
                hint = getString(R.string.settings_tesla_vin_label)
                setSingleLine()
                setText(launcherPreferences.loadTeslaVin())
                typeface = workSansRegular
                textSize = 16f
            }
        val baseUrlInput =
            EditText(this).apply {
                hint = getString(R.string.settings_tesla_base_url_label)
                setSingleLine()
                setText(launcherPreferences.loadTeslaBaseUrl(TeslaRepository.DEFAULT_BASE_URL))
                typeface = workSansRegular
                textSize = 16f
            }

        container.addView(tokenInput)
        container.addView(
            vinInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 10.dp
            },
        )
        container.addView(
            baseUrlInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 10.dp
            },
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_tesla_setup_title))
            .setMessage(getString(R.string.settings_tesla_setup_message))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val enteredToken = tokenInput.text?.toString().orEmpty().trim()
                if (enteredToken.isNotBlank()) {
                    launcherPreferences.saveTeslaAccessToken(enteredToken)
                }
                launcherPreferences.saveTeslaVin(vinInput.text?.toString().orEmpty())
                launcherPreferences.saveTeslaBaseUrl(baseUrlInput.text?.toString().orEmpty().ifBlank { TeslaRepository.DEFAULT_BASE_URL })
                if (launcherPreferences.isTeslaConfigured()) {
                    launcherPreferences.saveTeslaEnabled(true)
                }
                TeslaSyncScheduler.syncFromPreferences(this)
                renderTeslaHomeStatus()
                renderSettingsControls()
                Toast.makeText(this, R.string.settings_tesla_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshTeslaStatusNow() {
        if (!launcherPreferences.isTeslaConfigured()) {
            Toast.makeText(this, R.string.settings_tesla_requires_config, Toast.LENGTH_SHORT).show()
            showTeslaSetupDialog()
            return
        }

        backgroundExecutor.execute {
            val result =
                try {
                    TeslaRepository(this, launcherPreferences).fetchAndCacheBatteryStatus()
                } catch (exception: Exception) {
                    TeslaFetchResult.Failure(exception.message ?: "Tesla request failed.")
                }
            runOnUiThread {
                when (result) {
                    is TeslaFetchResult.Success -> {
                        renderTeslaHomeStatus()
                        renderSettingsControls()
                        Toast.makeText(this, R.string.settings_tesla_refresh_success, Toast.LENGTH_SHORT).show()
                    }

                    is TeslaFetchResult.Failure -> {
                        launcherPreferences.saveTeslaLastError(result.message)
                        Log.w(TAG, "Tesla refresh failed: ${result.message}")
                        renderSettingsControls()
                        val failureMessage =
                            getString(R.string.settings_tesla_refresh_failed_with_reason, result.message)
                        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun disconnectTesla() {
        launcherPreferences.clearTeslaCredentials()
        TeslaSyncScheduler.syncFromPreferences(this)
        renderTeslaHomeStatus()
        renderSettingsControls()
        Toast.makeText(this, R.string.settings_tesla_disconnected, Toast.LENGTH_SHORT).show()
    }

    private fun openAppInfo(app: LaunchableApp) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
            },
        )
    }

    private fun shareApp(app: LaunchableApp) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${app.label}\n${app.packageName}")
                },
                getString(R.string.action_share),
            ),
        )
    }

    private fun uninstallApp(app: LaunchableApp) {
        if (isSystemApp(app.packageName)) {
            Log.d(TAG, "Uninstall unavailable for system app ${app.packageName}; opening app info")
            Toast.makeText(this, R.string.system_app_uninstall_unavailable, Toast.LENGTH_SHORT).show()
            openAppInfo(app)
            return
        }

        val packageUri = Uri.fromParts("package", app.packageName, null)
        val uninstallIntent =
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = packageUri
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

        if (uninstallIntent.resolveActivity(packageManager) != null) {
            try {
                Log.d(TAG, "Launching uninstall activity for ${app.packageName} using ACTION_UNINSTALL_PACKAGE")
                uninstallLauncher.launch(uninstallIntent)
                return
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to launch ACTION_UNINSTALL_PACKAGE for ${app.packageName}", exception)
            }
        }

        val deleteIntent =
            Intent(Intent.ACTION_DELETE).apply {
                data = packageUri
            }

        if (deleteIntent.resolveActivity(packageManager) != null) {
            try {
                Log.d(TAG, "Launching uninstall activity for ${app.packageName} using ACTION_DELETE fallback")
                startActivity(deleteIntent)
                return
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to launch ACTION_DELETE for ${app.packageName}", exception)
            }
        }

        Log.w(TAG, "No uninstall flow available for ${app.packageName}; falling back to app info")
        Toast.makeText(this, R.string.uninstall_not_available, Toast.LENGTH_SHORT).show()
        openAppInfo(app)
    }

    private fun refreshDisplayedApps() {
        val restoredDefaultLabels = allApps.map { app -> app.copy(label = app.defaultLabel) }
        val customizedApps = applyLabelOverrides(restoredDefaultLabels, launcherPreferences.loadLabelOverrides())
        allApps = sortAppsByLabel(customizedApps)
        favoriteApps = resolveFavoriteApps(allApps)
        renderFavorites()
        renderState(currentState)
    }

    private fun resolveFavoriteApps(apps: List<LaunchableApp>): List<LaunchableApp> {
        if (!launcherPreferences.hasCustomFavorites()) {
            return sortAppsByLabel(appRepository.selectFavorites(apps, currentHomeFavoriteLimit()))
        }

        val appsByKey = apps.associateBy { componentKey(it) }
        val shortcutsByKey =
            launcherPreferences.loadWebShortcuts()
                .map { shortcut -> webShortcutKey(shortcut.id) to shortcut.toLaunchableApp() }
                .toMap()
        val savedFavorites =
            launcherPreferences.loadFavoriteComponentKeys().mapNotNull { componentKey ->
                appsByKey[componentKey] ?: shortcutsByKey[componentKey]
            }
        return sortAppsByLabel(savedFavorites)
    }

    private fun saveCustomFavorites(favorites: List<LaunchableApp>) {
        launcherPreferences.saveFavoriteComponentKeys(favorites.map(::componentKey))
    }

    private fun componentKey(app: LaunchableApp): String =
        app.webShortcutId?.let(::webShortcutKey) ?: app.componentName.flattenToString()

    private fun webShortcutKey(shortcutId: String): String = "$WEB_SHORTCUT_KEY_PREFIX$shortcutId"

    private fun LauncherPreferences.WebShortcut.toLaunchableApp(): LaunchableApp =
        LaunchableApp(
            label = label,
            packageName = CHROME_PACKAGE,
            componentName = ComponentName(WEB_SHORTCUT_COMPONENT_PACKAGE, "Shortcut_$id"),
            defaultLabel = label,
            webShortcutId = id,
            webUrl = url,
        )

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun applyLabelOverrides(
        apps: List<LaunchableApp>,
        overrides: Map<String, String>,
    ): List<LaunchableApp> {
        return apps.map { app ->
            val overrideLabel = overrides[componentKey(app)]?.trim().orEmpty()
            if (overrideLabel.isBlank()) {
                app
            } else {
                app.copy(label = overrideLabel)
            }
        }
    }

    private fun sortAppsByLabel(apps: List<LaunchableApp>): List<LaunchableApp> {
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        return apps.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }
}
