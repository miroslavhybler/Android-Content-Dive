plugins {
    id("contentdive.android-library")
    id("contentdive.maven-publish")
}

android {
    namespace = "com.contentdive.backend.appsearch"
}

dependencies {
    api(project(":contentdive-api"))
    implementation(project(":contentdive-engine"))
    implementation(project(":contentdive-spi"))
    implementation(libs.androidx.appsearch)
    implementation(libs.androidx.appsearch.local.storage)

    androidTestImplementation(testFixtures(project(":contentdive-spi")))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(kotlin("test-junit"))
}

tasks.named<GenerateAndroidBinaryApiTask>("apiBuild") {
    includedClassNames.set(
        listOf(
            "com.contentdive.backend.appsearch.AppSearchBackendFactoryKt",
            "com.contentdive.backend.appsearch.AppSearchContentDiveFactoryKt",
        ),
    )
}
