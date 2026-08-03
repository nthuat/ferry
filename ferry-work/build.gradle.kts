import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

// See :ferry's build.gradle.kts for what 0.x signals here.
group = "dev.thuat"
version = "0.1.0"

android {
    namespace = "dev.thuat.ferry.work"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Exposes the "release" variant as a software component maven-publish can attach a publication
    // to (components["release"], below) — an Android library does not have one by default the way
    // java-library's "java" component always does.
    //
    // withSourcesJar() only, not withJavadocJar(): AGP's own javadoc generation for a Kotlin Android
    // library runs a bundled Dokka internally, and it crashes parsing this module's own dependency
    // on :ferry's RepoProgress — a sealed interface, compiled with the JVM 17 PermittedSubclasses
    // attribute — with "PermittedSubclasses requires ASM9" (confirmed by actually running
    // publishToMavenLocal, not assumed). The javadocJar task below stands in for it instead.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Matches :ferry — a library has no excuse for warnings its consumers will inherit.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // api, not implementation: RepoDownloader, RepoProgress and Ferry's exception types are in
    // the signatures of RepoDownloadWorker and RepoDownloadWorkerFactory, so a consumer needs them
    // on its own compile classpath to construct either — the same reasoning EmbeddabilityTest
    // enforces on :ferry's own okhttp/coroutines dependencies.
    api(project(":ferry"))
    api("androidx.work:work-runtime-ktx:2.11.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.work:work-testing:2.11.2")
}

// Credentials, signing and the POM fields shared with :ferry live in gradle/publishing.gradle.kts.
apply(from = "$rootDir/gradle/publishing.gradle.kts")

// Stands in for android.publishing's own withJavadocJar() — see the comment where that's left off,
// above. Empty on purpose, the same Kotlin-only reason :ferry's own javadoc jar is empty (see its
// build.gradle.kts): Maven Central requires the artifact to exist, not that it be non-empty, and a
// real one is a separate, larger addition (Dokka, minus the crash above) than this task's scope.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        // afterEvaluate: components["release"] is the Android Gradle Plugin's own software
        // component for the variant android.publishing.singleVariant("release") registered above,
        // and AGP does not create it until the variant itself has been evaluated — unlike
        // java-library's "java" component, which :ferry's own publication can reference immediately.
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
                artifact(javadocJar)
            }
            pom {
                name.set("ferry-work")
                description.set(
                    "Optional WorkManager integration for Ferry — backgrounds a download as a " +
                        "CoroutineWorker, with a host-controlled foreground notification and " +
                        "WorkManager's own retry, backoff and uniqueness guarantee.",
                )
            }
        }
    }
}
