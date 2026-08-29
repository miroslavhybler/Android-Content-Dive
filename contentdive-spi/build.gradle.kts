plugins {
    id("contentdive.kotlin-library")
    id("contentdive.maven-publish")
    `java-test-fixtures`
}

dependencies {
    api(project(":contentdive-api"))
    testFixturesImplementation(kotlin("test-junit"))
}
