plugins {
    id("contentdive.kotlin-library")
    id("contentdive.maven-publish")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":contentdive-api"))
    api(libs.kotlinx.serialization.json)
}
