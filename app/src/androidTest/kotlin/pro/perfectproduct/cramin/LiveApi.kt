package pro.perfectproduct.cramin

/**
 * Маркер живых инструментированных тестов (реальный OpenRouter / сеть).
 * scripts/test-device.sh без флага исключает их (`notAnnotation`), с `--live` — запускает только их.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LiveApi
