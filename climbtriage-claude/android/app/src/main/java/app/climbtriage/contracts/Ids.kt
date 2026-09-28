package app.climbtriage.contracts

import java.security.SecureRandom

/** ULID-based identifiers: 48-bit ms timestamp + 80 random bits, Crockford base32 (26 chars). */
object Ids {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun ulid(nowMs: Long = System.currentTimeMillis()): String {
        val out = CharArray(26)
        var time = nowMs
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(time and 31).toInt()]
            time = time ushr 5
        }
        val bytes = ByteArray(10).also(random::nextBytes)
        // 80 random bits → 16 chars of 5 bits.
        var bitBuf = 0L
        var bits = 0
        var idx = 10
        for (b in bytes) {
            bitBuf = (bitBuf shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out[idx++] = ALPHABET[((bitBuf ushr bits) and 31).toInt()]
            }
        }
        return String(out)
    }

    fun new(prefix: String): String = "${prefix}_${ulid()}"
}
