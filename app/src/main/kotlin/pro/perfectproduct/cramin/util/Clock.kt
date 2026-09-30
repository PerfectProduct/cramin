package pro.perfectproduct.cramin.util

/** Источник времени, подменяемый в тестах. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
