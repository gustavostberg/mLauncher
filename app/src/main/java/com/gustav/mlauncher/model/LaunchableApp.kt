package com.gustav.mlauncher.model

import android.content.ComponentName
import android.content.Intent

data class LaunchableApp(
    val label: String,
    val packageName: String,
    val componentName: ComponentName,
    val defaultLabel: String = label,
) {
    fun buildLaunchIntent(): Intent {
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(componentName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
