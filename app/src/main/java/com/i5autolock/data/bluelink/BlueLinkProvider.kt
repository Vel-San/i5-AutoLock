package com.i5autolock.data.bluelink

import com.i5autolock.data.bluelink.eu.EuBlueLinkClient
import com.i5autolock.data.bluelink.fake.FakeBlueLinkClient
import com.i5autolock.data.metrics.ApiMetrics
import com.i5autolock.data.secure.SecureStore
import com.i5autolock.data.settings.AppSettings
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the right [BlueLinkClient] for the current settings and caches it.
 * Demo mode -> fake client. Otherwise a region-specific client. Every client is wrapped
 * in [MeteredBlueLinkClient] so all calls feed the Statistics screen.
 */
@Singleton
class BlueLinkProvider @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val httpClient: HttpClient,
    private val secureStore: SecureStore,
    private val fakeClient: FakeBlueLinkClient,
    private val metrics: ApiMetrics,
    private val activityLog: ActivityLog,
) {
    @Volatile private var cached: Pair<CacheKey, BlueLinkClient>? = null

    fun client(settings: AppSettings): BlueLinkClient {
        val key = CacheKey(settings.demoMode, settings.region)
        cached?.let { if (it.first == key) return it.second }
        val client = build(settings)
        cached = key to client
        return client
    }

    /** Region config exposed for the UI's OAuth login flow. */
    fun regionConfig(region: Region): RegionConfig = when (region) {
        Region.EU -> RegionConfig.euHyundai()
        // TODO: add US/CA/AU configs as they are implemented.
        else -> RegionConfig.euHyundai()
    }

    private fun build(settings: AppSettings): BlueLinkClient {
        val base = if (settings.demoMode) {
            fakeClient
        } else when (settings.region) {
            Region.EU -> EuBlueLinkClient(
                httpClient,
                RegionConfig.euHyundai(),
                secureStore,
                appContext,
                diag = { activityLog.add(LogLevel.INFO, it) },
            )
            else -> EuBlueLinkClient(
                httpClient,
                RegionConfig.euHyundai(),
                secureStore,
                appContext,
                diag = { activityLog.add(LogLevel.INFO, it) },
            )
        }
        return MeteredBlueLinkClient(base, metrics)
    }

    fun invalidate() { cached = null }

    private data class CacheKey(val demo: Boolean, val region: Region)
}
