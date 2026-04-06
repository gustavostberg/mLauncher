package com.gustav.mlauncher.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.gustav.mlauncher.R

object AppFonts {
    @Volatile
    private var regularTypeface: Typeface? = null

    @Volatile
    private var italicTypeface: Typeface? = null

    @Volatile
    private var mediumTypeface: Typeface? = null

    @Volatile
    private var lightTypeface: Typeface? = null

    @Volatile
    private var extraLightTypeface: Typeface? = null

    fun regular(context: Context): Typeface =
        regularTypeface
            ?: synchronized(this) {
                regularTypeface
                    ?: (ResourcesCompat.getFont(context, R.font.work_sans_regular) ?: Typeface.SANS_SERIF).also {
                        regularTypeface = it
                    }
            }

    fun italic(context: Context): Typeface =
        italicTypeface
            ?: synchronized(this) {
                italicTypeface
                    ?: (ResourcesCompat.getFont(context, R.font.work_sans_italic)
                        ?: Typeface.create(regular(context), Typeface.ITALIC)).also {
                            italicTypeface = it
                        }
            }

    fun medium(context: Context): Typeface =
        mediumTypeface
            ?: synchronized(this) {
                mediumTypeface
                    ?: (ResourcesCompat.getFont(context, R.font.work_sans_medium) ?: regular(context)).also {
                        mediumTypeface = it
                    }
            }

    fun light(context: Context): Typeface =
        lightTypeface
            ?: synchronized(this) {
                lightTypeface
                    ?: (ResourcesCompat.getFont(context, R.font.work_sans_light) ?: regular(context)).also {
                        lightTypeface = it
                    }
            }

    fun extraLight(context: Context): Typeface =
        extraLightTypeface
            ?: synchronized(this) {
                extraLightTypeface
                    ?: (ResourcesCompat.getFont(context, R.font.work_sans_extra_light) ?: light(context)).also {
                        extraLightTypeface = it
                    }
            }
}
