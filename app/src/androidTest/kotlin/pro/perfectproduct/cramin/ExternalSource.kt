package pro.perfectproduct.cramin

/** Free public networking, excluded from the deterministic offline device suite. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExternalSource
