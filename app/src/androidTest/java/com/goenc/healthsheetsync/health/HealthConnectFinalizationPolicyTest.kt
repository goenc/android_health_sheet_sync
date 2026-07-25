package com.goenc.healthsheetsync.health

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectFinalizationPolicyTest {
    @Test
    fun doesNotFinalizeWhenHealthConnectIsUnavailable() {
        assertFalse(
            shouldFinalizePastDailyEnergySnapshotsAfterHealthConnectSync(
                availability = HealthConnectAvailability.Unavailable("利用不可"),
                hasMissingPermissions = false,
                synchronizationSucceeded = true,
            ),
        )
    }

    @Test
    fun doesNotFinalizeWhenPermissionsAreMissing() {
        assertFalse(
            shouldFinalizePastDailyEnergySnapshotsAfterHealthConnectSync(
                availability = HealthConnectAvailability.Available,
                hasMissingPermissions = true,
                synchronizationSucceeded = true,
            ),
        )
    }

    @Test
    fun doesNotFinalizeWhenSynchronizationFails() {
        assertFalse(
            shouldFinalizePastDailyEnergySnapshotsAfterHealthConnectSync(
                availability = HealthConnectAvailability.Available,
                hasMissingPermissions = false,
                synchronizationSucceeded = false,
            ),
        )
    }

    @Test
    fun finalizesAfterSuccessfulSynchronizationEvenWhenNothingChanged() {
        assertTrue(
            shouldFinalizePastDailyEnergySnapshotsAfterHealthConnectSync(
                availability = HealthConnectAvailability.Available,
                hasMissingPermissions = false,
                synchronizationSucceeded = true,
            ),
        )
    }
}
