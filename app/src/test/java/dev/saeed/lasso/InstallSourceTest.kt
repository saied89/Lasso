package dev.saeed.lasso

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceTest {
    @Test
    fun installsFromAFileAreRestrictedOnAndroid13AndUp() {
        assertTrue(isRestrictedSource(33, PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE))
        assertTrue(isRestrictedSource(37, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE))
    }

    @Test
    fun storeAndAdbInstallsAreNot() {
        assertFalse(isRestrictedSource(37, PackageInstaller.PACKAGE_SOURCE_STORE))
        assertFalse(isRestrictedSource(37, PackageInstaller.PACKAGE_SOURCE_OTHER)) // adb install
        assertFalse(isRestrictedSource(37, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED))
    }

    @Test
    fun olderAndroidHasNoRestriction() {
        assertFalse(isRestrictedSource(32, PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE))
    }
}
