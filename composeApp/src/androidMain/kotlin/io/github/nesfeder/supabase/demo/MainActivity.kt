package io.github.nesfeder.supabase.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.OfflineLogger
import io.github.nesfeder.supabase.SupabaseOfflineClient
import io.github.nesfeder.supabase.SupabaseOfflineConfig
import io.github.nesfeder.supabase.createSupabaseOfflineClient
import io.github.nesfeder.supabase.demo.util.AndroidConnectivityObserver
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

class MainActivity : ComponentActivity() {

    private lateinit var offlineClient: SupabaseOfflineClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val supabase = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Postgrest)
            install(Storage)
        }

        offlineClient = createSupabaseOfflineClient(
            context = this,
            supabase = supabase,
            config = SupabaseOfflineConfig(
                logger = OfflineLogger { level, message ->
                    when (level) {
                        OfflineLogLevel.DEBUG -> Log.d(LOG_TAG, message)
                        OfflineLogLevel.INFO -> Log.i(LOG_TAG, message)
                        OfflineLogLevel.WARNING -> Log.w(LOG_TAG, message)
                        OfflineLogLevel.ERROR -> Log.e(LOG_TAG, message)
                    }
                },
                minLogLevel = OfflineLogLevel.DEBUG,
            ),
        )

        val connectivity = AndroidConnectivityObserver(applicationContext)

        setContent {
            DemoApp(offlineClient = offlineClient, connectivity = connectivity)
        }
    }

    private companion object {
        const val LOG_TAG = "SupabaseOfflineDemo"
    }
}
