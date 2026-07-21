package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrInterceptor
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.coroutines.CoroutineScope
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
    scope: CoroutineScope,
) {

    val cookieJar = AndroidCookieJar()

    val clientBuilder: OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .addNetworkInterceptor(BrotliInterceptor)
            // TLMR -->
            .addInterceptor(FlareSolverrInterceptor(preferences))
        // <-- TLMR

        if (preferences.verboseLogging.get()) {
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        when (preferences.dohProvider.get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()

            PREF_DOH_GOOGLE -> builder.dohGoogle()

            PREF_DOH_ADGUARD -> builder.dohAdGuard()

            PREF_DOH_QUAD9 -> builder.dohQuad9()

            PREF_DOH_ALIDNS -> builder.dohAliDNS()

            PREF_DOH_DNSPOD -> builder.dohDNSPod()

            PREF_DOH_360 -> builder.doh360()

            PREF_DOH_QUAD101 -> builder.dohQuad101()

            PREF_DOH_MULLVAD -> builder.dohMullvad()

            PREF_DOH_CONTROLD -> builder.dohControlD()

            PREF_DOH_NJALLA -> builder.dohNajalla()

            PREF_DOH_SHECAN -> builder.dohShecan()

            PREF_DOH_LIBREDNS -> builder.dohLibreDNS()

            PREF_DOH_CUSTOM -> {
                val custom = preferences.dohCustomUrl.get().trim()
                if (custom.isNotEmpty()) {
                    try {
                        // Validate URL early
                        custom.toHttpUrl()

                        // Parse optional bootstrap hosts from comma-separated preference
                        val bootstrapPref = preferences.dohCustomBootstrap.get().trim()
                        val bootstrapHosts = if (bootstrapPref.isNotEmpty()) {
                            bootstrapPref.split(',')
                                .mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
                                .mapNotNull { host ->
                                    try {
                                        java.net.InetAddress.getByName(host)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                        } else {
                            emptyList()
                        }

                        builder.dohCustom(custom, bootstrapHosts)
                    } catch (e: Exception) {
                        // Invalid URL: fall back to no DoH
                        builder
                    }
                } else {
                    builder
                }
            }

            else -> builder
        }
    }

    // A smart bridge interceptor that intercepts the crash check for newer extensions
    private val dynamicGzipInterceptor = Interceptor { chain ->
        val request = chain.request()
        val isAllMangaNew = request.url.host.contains("allmanga", ignoreCase = true)
        
        if (isAllMangaNew) {
            // Bypass the Gzip rule entirely for modern extensions to prevent crashes
            chain.proceed(request)
        } else {
            // Apply old Gzip rule handling to keep legacy extensions alive
            IgnoreGzipInterceptor().intercept(chain)
        }
    }

    // FIXED: Formed standard built instance first, then created standard client mirrors
    private val baseCleanClient = clientBuilder.build()

    // Unified client that presents itself to both old and new extension structures safely
    val defaultClient = baseCleanClient
        .newBuilder()
        .addNetworkInterceptor(dynamicGzipInterceptor)
        .build()

    // Primary application client with Cloudflare capabilities
    val client = baseCleanClient
        .newBuilder()
        .addInterceptor(
            CloudflareInterceptor(context, cookieJar, preferences, scope) { defaultUserAgentProvider() },
        )
        .build()

    val nonCloudflareClient = defaultClient

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.get().trim()
}
