package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebridProviderTest {
    @Test
    fun `torbox exposes local addon capabilities`() {
        assertTrue(DebridProviders.Torbox.authMethod == DebridProviderAuthMethod.DeviceCode)
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.ClientResolve))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.LocalTorrentResolve))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.CloudLibrary))
    }

    @Test
    fun `premiumize exposes oauth and cloud service capabilities`() {
        assertTrue(DebridProviders.Premiumize.visibleInUi)
        assertTrue(DebridProviders.Premiumize.authMethod == DebridProviderAuthMethod.DeviceCode)
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.ClientResolve))
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.LocalTorrentResolve))
        assertTrue(DebridProviders.Premiumize.supports(DebridProviderCapability.CloudLibrary))
    }

    @Test
    fun `real debrid signs in with a code and supports the same paths as torbox`() {
        assertTrue(DebridProviders.RealDebrid.authMethod == DebridProviderAuthMethod.DeviceCode)
        assertTrue(DebridProviders.RealDebrid.visibleInUi)
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.ClientResolve))
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentResolve))
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.CloudLibrary))
    }
}
