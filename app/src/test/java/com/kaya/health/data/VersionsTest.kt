package com.kaya.health.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun newerVersions() {
        assertTrue(Versions.isNewer("v1.1.2", "1.1.1"))
        assertTrue(Versions.isNewer("1.2.0", "1.1.9"))
        assertTrue(Versions.isNewer("1.10.0", "1.9.0")) // numbers, not text
        assertTrue(Versions.isNewer("2", "1.9.9"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("v1.1.1", "1.1.1"))
        assertFalse(Versions.isNewer("1.1.0", "1.1.1"))
        assertFalse(Versions.isNewer("1.1", "1.1.0"))
        assertFalse(Versions.isNewer("1.1.1-beta", "1.1.1"))
    }
}
