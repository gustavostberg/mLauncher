package com.gustav.mlauncher.search

import com.gustav.mlauncher.model.LaunchableApp
import java.text.Collator
import java.util.Locale

object AppSearch {
    fun filter(apps: List<LaunchableApp>, query: String, locale: Locale = Locale.getDefault()): List<LaunchableApp> {
        val normalizedQuery = query.trim().lowercase(locale)
        if (normalizedQuery.isEmpty()) {
            return apps
        }

        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
        }

        return apps
            .mapNotNull { app ->
                val normalizedLabel = app.label.lowercase(locale)
                val rank =
                    when {
                        normalizedLabel == normalizedQuery -> 0
                        normalizedLabel.startsWith(normalizedQuery) -> 1
                        normalizedLabel
                            .split(' ', '-', '_', '.')
                            .any { token -> token.startsWith(normalizedQuery) } -> 2
                        normalizedLabel.contains(normalizedQuery) -> 3
                        else -> null
                    } ?: return@mapNotNull null

                RankedApp(app = app, rank = rank)
            }
            .sortedWith { left, right ->
                val rankComparison = left.rank.compareTo(right.rank)
                if (rankComparison != 0) {
                    rankComparison
                } else {
                    collator.compare(left.app.label, right.app.label)
                }
            }
            .map { rankedApp -> rankedApp.app }
    }

    private data class RankedApp(
        val app: LaunchableApp,
        val rank: Int,
    )
}
