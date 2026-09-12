package app.kaeru

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.tv.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Kaeru TV") }
    }
}
