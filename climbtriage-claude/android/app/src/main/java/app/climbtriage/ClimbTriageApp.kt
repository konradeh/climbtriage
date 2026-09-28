package app.climbtriage

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.climbtriage.analysis.SessionAnalyzer
import app.climbtriage.data.AppDatabase
import app.climbtriage.data.SessionRepository

class ClimbTriageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual DI: one place that owns the long-lived objects. */
class AppContainer(context: Context) {
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "climbtriage.db").build()
    val repository = SessionRepository(context, db)
    val analyzer = SessionAnalyzer(context, repository)
    val settings = Settings(context)
}

/** Local preferences. The backend token here is a deployment token, never a model-provider key. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("climbtriage", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = prefs.getString("backendUrl", BuildConfig.DEFAULT_BACKEND_URL)!!
        set(v) = prefs.edit().putString("backendUrl", v.trim()).apply()

    var backendToken: String?
        get() = prefs.getString("backendToken", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("backendToken", v).apply()
}

val Context.container: AppContainer get() = (applicationContext as ClimbTriageApp).container
