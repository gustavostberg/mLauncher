package com.gustav.mlauncher.data

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.gustav.mlauncher.model.LaunchableApp
import java.text.Collator
import java.util.Locale

class AppRepository {
    private data class FavoriteSpec(
        val packageNames: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
    )

    private val defaultFavoriteSpecs =
        listOf(
            FavoriteSpec(
                packageNames = listOf("com.android.dialer", "com.google.android.dialer"),
                labels = listOf("Phone", "Telefon"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.google.android.apps.messaging", "com.android.mms"),
                labels = listOf("Messages", "Meddelanden", "Meddelande"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.android.chrome"),
                labels = listOf("Chrome"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.google.android.apps.maps"),
                labels = listOf("Maps", "Kartor"),
            ),
            FavoriteSpec(
                packageNames =
                    listOf(
                        "com.google.android.GoogleCamera",
                        "com.android.camera",
                        "org.codeaurora.snapcam",
                    ),
                labels = listOf("Camera", "Kamera"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.google.android.calculator", "com.android.calculator2"),
                labels = listOf("Calculator", "Kalkylator", "Google Calculator"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.google.android.gm"),
                labels = listOf("Gmail"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.google.android.apps.photos", "com.android.gallery3d"),
                labels = listOf("Photos", "Foton", "Gallery", "Galleri"),
            ),
            FavoriteSpec(
                packageNames = listOf("com.android.settings"),
                labels = listOf("Settings", "Inställningar"),
            ),
        )

    fun loadLaunchableApps(packageManager: PackageManager, selfPackageName: String): List<LaunchableApp> {
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return queryLaunchableActivities(packageManager, launchIntent)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                if (activityInfo.packageName == selfPackageName) {
                    return@mapNotNull null
                }

                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) {
                    return@mapNotNull null
                }

                LaunchableApp(
                    label = label,
                    packageName = activityInfo.packageName,
                    componentName = ComponentName(activityInfo.packageName, activityInfo.name),
                )
            }
            .distinctBy { it.packageName }
            .sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    fun selectFavorites(apps: List<LaunchableApp>, maxFavorites: Int = 9): List<LaunchableApp> {
        val selected = mutableListOf<LaunchableApp>()
        val remaining = apps.toMutableList()

        for (favoriteSpec in defaultFavoriteSpecs) {
            val match =
                remaining.firstOrNull { app ->
                    app.packageName in favoriteSpec.packageNames ||
                        favoriteSpec.labels.any { candidate ->
                            app.defaultLabel.equals(candidate, ignoreCase = true)
                        }
                } ?: continue
            selected += match
            remaining -= match
            if (selected.size == maxFavorites) {
                return selected
            }
        }

        return selected + remaining.take(maxFavorites - selected.size)
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableActivities(packageManager: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
}
