import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.contentdive.example"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.contentdive.example"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":contentdive-backend-appsearch"))
    implementation(project(":contentdive-fuzzy"))
    implementation(project(":contentdive-compose"))
    implementation(project(":contentdive-ksp-annotations"))
    implementation(project(":contentdive-serialization-kotlinx"))
    implementation(project(":contentdive-navigation3"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation3.ui)
    testImplementation(libs.junit)
    testImplementation(project(":contentdive-backend-memory"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(kotlin("test-junit"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    ksp(project(":contentdive-ksp-processor"))
}

abstract class CheckStandardConsumerClasspathTask : DefaultTask() {
    @get:Input
    abstract val componentNames: SetProperty<String>

    @TaskAction
    fun verify() {
        val forbiddenArtifacts = setOf(
            "contentdive-spi",
            "contentdive-engine",
            "contentdive-ksp-processor",
        )
        val leaks = componentNames.get()
            .filter { component -> forbiddenArtifacts.any(predicate = component::contains) }
            .sorted()

        check(leaks.isEmpty()) {
            "Standard ContentDive consumer unexpectedly exposes implementation artifacts: " +
                    leaks.joinToString()
        }
    }
}

val checkStandardConsumerClasspath by tasks.registering(CheckStandardConsumerClasspathTask::class) {
    group = "verification"
    description =
        "Checks that the standard app compile classpath contains no implementation artifacts."
    componentNames.convention(emptySet())
}

configurations.matching { configuration ->
    configuration.name in setOf(
        "debugCompileClasspath",
        "debugUnitTestCompileClasspath",
        "debugAndroidTestCompileClasspath",
    )
}.all {
    val names = incoming.resolutionResult.rootComponent.map { root ->
        val pending = ArrayDeque<ResolvedComponentResult>().apply { add(root) }
        val visited = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (visited.add(component.id.displayName)) {
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { dependency -> pending.add(dependency.selected) }
            }
        }
        visited
    }
    checkStandardConsumerClasspath.configure {
        componentNames.addAll(names)
    }
}

tasks.named("check") {
    dependsOn(checkStandardConsumerClasspath)
}
