package com.mihai.navhud

/**
 * One User-Agent for every outbound request.
 *
 * There were five, hand-written, and they had drifted to 1.0, 1.1, 1.4, 1.6
 * and 1.8 while the app was on 1.18 -- so the Overpass and Nominatim operators
 * whose free service this leans on were being told five different lies about
 * who was calling. Derived from BuildConfig, it cannot drift again.
 *
 * Nominatim's usage policy asks for an identifiable agent, and Overpass asks
 * the same; this is how a DIY app stays a welcome guest on someone else's
 * server.
 */
object Net {
    val USER_AGENT: String =
        "NavHUD/${BuildConfig.VERSION_NAME} (DIY car head-up display)"
}
