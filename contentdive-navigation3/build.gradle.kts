plugins {
    id("contentdive.android-library")
    id("contentdive.maven-publish")
}

android {
    namespace = "com.contentdive.navigation3"
}

dependencies {
    api(project(":contentdive-api"))
    api(libs.androidx.navigation3.runtime)
}
