package com.infinifolder.viewer

import com.infinifolder.theme.WinampSkinParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class MediaAssetOwnershipTest {
    @Test fun packagedWinampSkinsArePresentAndParse() {
        val root = File(requireNotNull(System.getProperty("infini.media.skins")))
        val names = listOf("Bento_Classified.wsz", "Winamp3_Classified_v5.5.wsz", "Winamp5_Classified_v5.5.wsz", "base-2.91.wsz")
        for (name in names) {
            val file = File(root, name)
            assertTrue("Missing packaged skin: $name", file.isFile)
            val parsed = file.inputStream().use { WinampSkinParser.parseSkinFromStream(it, name) }
            assertNotNull("Cannot parse $name", parsed)
            assertTrue("Missing main artwork: $name", parsed!!.artwork.containsKey("main.bmp"))
            if (name == "base-2.91.wsz") {
                for (part in listOf("cbuttons.bmp", "titlebar.bmp", "posbar.bmp", "shufrep.bmp", "numbers.bmp")) {
                    assertTrue("Missing $part", parsed.artwork.containsKey(part))
                }
            }
        }
    }

    @Test fun optionalSoundFontRetainsItsOriginalBytes() {
        val root = File(requireNotNull(System.getProperty("infini.media.soundfonts")))
        val bank = File(root, "FluidR3Mono_GM.sf3")
        assertEquals(14540067L, bank.length())
        bank.inputStream().use { input ->
            val header = ByteArray(12)
            assertEquals(12, input.read(header))
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("sfbk", String(header, 8, 4, Charsets.US_ASCII))
        }
        val hash = MessageDigest.getInstance("SHA-256")
        bank.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        assertEquals("bac3363e097d182d2659db2e84a00f1c68d6c712181addc77c9f51da23a24897", hash.digest().joinToString("") { "%02x".format(it) })
        assertTrue(File(root, "FluidR3Mono_License.md").isFile)
    }
}
