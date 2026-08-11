package com.example.caranc.shared

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
actual annotation class Keep()
