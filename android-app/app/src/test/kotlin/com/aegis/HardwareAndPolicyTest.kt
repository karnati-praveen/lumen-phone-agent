package com.aegis

import com.aegis.hardware.HardwareProfile
import com.aegis.hardware.HardwareProfiler
import com.aegis.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareAndPolicyTest {

    @Test
    fun hardwareProfilesStayUnderAndroid17PeakCeiling() {
        for (profile in HardwareProfile.entries) {
            val totalMb = profile.totalRamGb * 1024.0
            val ceilingPct = (profile.safeCeilingMb / totalMb) * 100.0
            assertTrue("${profile.code} ceiling should stay < 30%", ceilingPct < 30.0)
            assertTrue("${profile.code} idle cap <= 50 MB", profile.idleCapMb <= 50)
            assertTrue("${profile.code} context >= 2048", profile.maxContextTokens >= 2048)
        }
        assertFalse(HardwareProfile.PROFILE_A.allowGlassBlurShaders)
        assertFalse(HardwareProfile.PROFILE_B.allowGlassBlurShaders)
        assertTrue(HardwareProfile.PROFILE_C.allowGlassBlurShaders)
    }

    @Test
    fun ggufModelCatalogChecksumsAndSizesAreValid() {
        assertTrue(ModelCatalog.entries.size >= 2)
        for (entry in ModelCatalog.entries) {
            assertEquals(64, entry.expectedSha256.length)
            assertTrue(entry.expectedBytes > 100_000_000L)
            assertTrue(entry.url.startsWith("https://huggingface.co/"))
            assertTrue(entry.filename.endsWith(".gguf"))
            if (entry.mmprojFilename != null) {
                assertEquals(64, entry.mmprojExpectedSha256.length)
                assertTrue(entry.mmprojExpectedBytes > 50_000_000L)
            }
        }
        val mid6Gb = ModelCatalog.recommendedForRamGb(6)
        assertEquals(ModelCatalog.SMOLVLM_500M.id, mid6Gb.id)
        val peak6Gb = HardwareProfiler.estimatePeakMb(mid6Gb.expectedBytes + mid6Gb.mmprojExpectedBytes, 768)
        assertTrue("6 GB VLM peak ($peak6Gb MB) must fit 6 GB ceiling", peak6Gb < HardwareProfile.PROFILE_B.safeCeilingMb)
    }
}
