package com.prosync.crmcompanion

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

fun AppCompatActivity.applyProSyncSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, true)
    window.statusBarColor = ContextCompat.getColor(this, R.color.app_background)
    window.navigationBarColor = ContextCompat.getColor(this, R.color.app_background)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }
}
