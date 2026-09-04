plugins {
    id("contentdive.kotlin-library")
    id("contentdive.maven-publish")
}

dependencies {
    api(project(":contentdive-spi"))
    implementation(project(":contentdive-fuzzy"))
}
