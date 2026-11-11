package com.infinimedia

import android.app.Application
import com.infinifolder.theme.ThemeManager

class InfiniMediaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.defaultAudioPlayerSkin = "skins/Infini.wsz"
    }
}
