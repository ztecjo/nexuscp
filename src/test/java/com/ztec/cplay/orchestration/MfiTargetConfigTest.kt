package com.ztec.cplay.orchestration

import com.ztec.cplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfiTargetConfigTest {
    @Test
    fun localTargetDoesNotRequireExternalDevices() {
        val config = config(mfiTarget = MfiTarget.LOCAL)
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
    }

    @Test
    fun remoteTargetDoesNotRequireALocalDevice() {
        val config = config(
            mfiTarget = MfiTarget.REMOTE,
            remoteMfiServer = "https://mfi.example.test",
            remoteMfiToken = "secret",
        )

        assertEquals(MfiTarget.REMOTE, config.mfiTarget)
        assertEquals("https://mfi.example.test", config.remoteMfiServer)
        assertEquals("secret", config.remoteMfiToken)
    }

    @Test
    fun remoteTargetRequiresAServerAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            config(mfiTarget = MfiTarget.REMOTE, remoteMfiServer = "  ")
        }
    }

    private fun config(
        mfiTarget: MfiTarget,
        remoteMfiServer: String? = null,
        remoteMfiToken: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        remoteMfiServer = remoteMfiServer,
        remoteMfiToken = remoteMfiToken,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
