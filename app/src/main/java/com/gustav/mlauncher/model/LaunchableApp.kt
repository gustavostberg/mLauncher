package com.gustav.mlauncher.model

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val componentName: ComponentName,
    val defaultLabel: String = label,
    val webShortcutId: String? = null,
    val webUrl: String? = null,
) {
    val isWebShortcut: Boolean
        get() = !webShortcutId.isNullOrBlank() && !webUrl.isNullOrBlank()

    fun buildLaunchIntent(): Intent {
        webUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(componentName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
