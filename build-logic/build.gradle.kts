plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "contentdive.kotlin-library"
            implementationClass = "ContentDiveKotlinLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "contentdive.android-library"
            implementationClass = "ContentDiveAndroidLibraryConventionPlugin"
        }
        register("kspProcessor") {
            id = "contentdive.ksp-processor"
            implementationClass = "ContentDiveKspProcessorConventionPlugin"
        }
        register("mavenPublish") {
            id = "contentdive.maven-publish"
            implementationClass = "ContentDiveMavenPublishConventionPlugin"
        }
    }
}
