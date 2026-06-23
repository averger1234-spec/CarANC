package com.example.caranc.shared

/**
 * iOS version / wiring of AncSessionContext (P2).
 *
 * Provides explicit IosGlobalAncSessionContext for use in iOS-specific code (view models, services, app delegate).
 * Mirrors the GlobalAncSessionContext pattern from common (TierManager.kt).
 *
 * Currently identical (common impl). Extend here in future for iOS-only providers:
 *   e.g. iOS location/speed, iOS audio route, platform entitlement checks, etc.
 * without affecting Android or requiring DI yet.
 *
 * Usage in iOS: val sessionContext = IosGlobalAncSessionContext
 * (parallel to android MainViewModel / ANCService using Global)
 *
 * Add comments for DI: later this can be provided/injected instead of global.
 */
val IosGlobalAncSessionContext = AncSessionContext()
