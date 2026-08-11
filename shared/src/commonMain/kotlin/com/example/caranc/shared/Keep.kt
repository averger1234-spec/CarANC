package com.example.caranc.shared

/**
 * Multiplatform stand-in for [androidx.annotation.Keep].
 * Android actual → androidx; iOS actual → no-op annotation (ProGuard N/A on Native).
 * Removes androidx.annotation from commonMain so Kotlin/Native can compile without ABI clash.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FILE,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.FIELD
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
expect annotation class Keep()
