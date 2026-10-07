package eu.studiodeanna.openchecklists.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ShareLinksTest {
    @Test
    fun recognisesNextcloudLinks() {
        val expected = ParsedLink.Nextcloud("https://cloud.example.com", "AbC123xyz")
        assertEquals(expected, ShareLinks.parse("https://cloud.example.com/s/AbC123xyz"))
        assertEquals(expected, ShareLinks.parse(" https://cloud.example.com/index.php/s/AbC123xyz/ "))
        assertEquals(expected, ShareLinks.parse("https://cloud.example.com/s/AbC123xyz/download"))
        assertEquals(
            ParsedLink.Nextcloud("https://example.com/nextcloud", "Tok3n"),
            ShareLinks.parse("https://example.com/nextcloud/index.php/s/Tok3n?dir=/"),
        )
    }

    @Test
    fun recognisesGoogleDriveLinks() {
        assertEquals(
            ParsedLink.GoogleDrive("1aB-cD_eF"),
            ShareLinks.parse("https://drive.google.com/file/d/1aB-cD_eF/view?usp=sharing"),
        )
        assertEquals(ParsedLink.GoogleDrive("1aB"), ShareLinks.parse("https://drive.google.com/open?id=1aB"))
        assertEquals(
            ParsedLink.GoogleDrive("1aB", "0-xY"),
            ShareLinks.parse("https://drive.google.com/file/d/1aB/view?usp=sharing&resourcekey=0-xY"),
        )
    }

    @Test
    fun linksCanNameOneListInAFolder() {
        assertEquals(
            ParsedLink.Nextcloud("https://cloud.example.com", "Tok", "Groceries (2).json"),
            ShareLinks.parse("https://cloud.example.com/index.php/s/Tok?file=Groceries+%282%29.json"),
        )
        val stored = ShareLinks.normalize(ShareLink("https://cloud.example.com/index.php/s/Tok?file=A.json", "pw"))
        assertEquals(ShareLink("https://cloud.example.com/s/Tok", "pw", "A.json"), stored)
        assertEquals("https://cloud.example.com/s/Tok?file=A.json", ShareLinks.shareable(stored))
    }

    @Test
    fun rejectsOtherLinks() {
        assertEquals(ParsedLink.Unrecognized, ShareLinks.parse("https://example.com/files/list.json"))
    }
}
