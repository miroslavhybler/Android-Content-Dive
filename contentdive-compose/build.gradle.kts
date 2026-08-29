plugins {
    id("contentdive.android-library")
    id("contentdive.maven-publish")
}

android {
    namespace = "com.contentdive.compose"
}

dependencies {
    api(project(":contentdive-api"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.text)

    testImplementation(project(":contentdive-backend-memory"))
    testImplementation(project(":contentdive-ksp-annotations"))
    testImplementation(project(":contentdive-ksp-processor"))
    testImplementation(libs.kotlin.compile.testing.ksp)
}

tasks.named<GenerateAndroidBinaryApiTask>("apiBuild") {
    includedClassNames.set(
        listOf("com.contentdive.compose.AnnotatedStringSearchFragmentKt"),
    )
}
