plugins {
    id("contentdive.kotlin-library")
    id("contentdive.maven-publish")
}

dependencies {
    api(project(":contentdive-api"))
    implementation(project(":contentdive-engine"))
    implementation(project(":contentdive-spi"))
    testImplementation(testFixtures(project(":contentdive-spi")))
}
