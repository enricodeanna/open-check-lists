package eu.studiodeanna.openchecklists.google

import kotlin.test.Test
import kotlin.test.assertEquals

class PkceTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesKnownDigests() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        // Spans several blocks.
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
        )
    }

    @Test
    fun codeChallengeMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceOAuthClient.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun readsRedirectParameters() {
        assertEquals(
            mapOf("state" to "s1", "code" to "4/0A b"),
            PkceOAuthClient.queryParams("http://127.0.0.1:5000/?state=s1&code=4%2F0A%20b"),
        )
        assertEquals(
            mapOf("state" to "s2", "code" to "c"),
            PkceOAuthClient.queryParams("com.googleusercontent.apps.123-abc:/oauth2redirect?state=s2&code=c#"),
        )
    }
}
