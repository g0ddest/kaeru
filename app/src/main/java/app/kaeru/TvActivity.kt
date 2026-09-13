package app.kaeru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.kaeru.ui.tv.TvApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvApp() }
    }
}
