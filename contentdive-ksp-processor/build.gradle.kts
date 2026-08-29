plugins {
    id("contentdive.ksp-processor")
    id("contentdive.maven-publish")
}

dependencies {
    implementation(project(":contentdive-ksp-annotations"))
    implementation(project(":contentdive-api"))
}
