/*
 * Shared by :ferry's and :ferry-work's own build.gradle.kts, via
 * `apply(from = "$rootDir/gradle/publishing.gradle.kts")` — after each has already applied the
 * `maven-publish` and `signing` plugins itself, via its own `plugins {}` block, which is what gives
 * each of them the sugared `publishing { }` accessor for the module-specific publication it still
 * defines on its own (which software component to publish, and how its sources and javadoc jars are
 * built, differ between `java-library` and an Android library variant).
 *
 * A script loaded via `apply(from = ...)`, unlike a module's own build.gradle.kts, can never have its
 * own `plugins {}` block at all — so this file falls back to `extensions.configure<T>` in place of
 * that same sugar, regardless of where the plugin was actually applied from. What it configures is
 * only what is genuinely identical between the two modules: credentials, the signing gate, the
 * destination repository, and the POM fields that describe this repository rather than one artifact
 * in it.
 */

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

/**
 * [gradleProperty] from `gradle.properties` or `-P`, falling back to [envVar] (a CI secret) — a
 * contributor with neither gets null here, not a build failure. That is what keeps `./gradlew test`,
 * and even `./gradlew publishToMavenLocal`, working with no Maven Central account and no signing key:
 * every call site below only acts when this returns non-null for everything it needs.
 */
fun secretOrNull(gradleProperty: String, envVar: String): String? =
    (findProperty(gradleProperty) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envVar)?.takeIf { it.isNotBlank() }

extensions.configure<PublishingExtension> {
    // Only the fields that describe this repository, not one specific artifact in it — name and
    // description stay in each module's own build.gradle.kts, next to the publication that needs them.
    publications.withType<MavenPublication>().configureEach {
        pom {
            url.set("https://github.com/nthuat/ferry")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("nthuat")
                    name.set("Thuat Nguyen")
                    email.set("thuat26.ng@gmail.com")
                    url.set("https://thuat.dev")
                }
            }
            scm {
                url.set("https://github.com/nthuat/ferry")
                connection.set("scm:git:https://github.com/nthuat/ferry.git")
                developerConnection.set("scm:git:ssh://git@github.com/nthuat/ferry.git")
            }
        }
    }

    repositories {
        maven {
            name = "mavenCentral"
            // Central Portal's OSSRH-compatible staging endpoint, current as of this writing —
            // Sonatype has moved this more than once, so confirm it against their own publishing
            // docs before relying on it. See the README's publishing note for the rest of what a
            // maintainer needs before this URL is ever actually used.
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = secretOrNull("mavenCentralUsername", "MAVEN_CENTRAL_USERNAME")
                password = secretOrNull("mavenCentralPassword", "MAVEN_CENTRAL_PASSWORD")
            }
        }
    }
}

val signingKey = secretOrNull("signingInMemoryKey", "SIGNING_IN_MEMORY_KEY")
val signingPassword = secretOrNull("signingInMemoryKeyPassword", "SIGNING_IN_MEMORY_KEY_PASSWORD")

// Configured only when both secrets are present. The alternative — always calling
// useInMemoryPgpKeys/sign and letting them fail at task-execution time when a key is missing — would
// still make publishToMavenLocal depend on a Sign task for someone with no key at all, which is
// exactly what a contributor cloning this must never need. No key, no Sign task, nothing to fail.
if (signingKey != null && signingPassword != null) {
    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(extensions.getByType<PublishingExtension>().publications)
    }

    // :ferry attaches one shared javadoc jar to every per-target KMP publication (its own
    // build.gradle.kts). Signing derives an artifact's .asc path from the artifact's filename alone,
    // not from which publication asked for it, so two Sign tasks for two different publications can
    // both claim to produce the exact same .asc file. Gradle's own task-output validation then fails
    // a publish task for consuming a file with no producer it was told about — it has no way to know
    // the two Sign tasks agree. Declaring every publish task dependent on every Sign task is the
    // standard fix for this exact shared-artifact-in-a-multi-publication shape (tracked upstream at
    // https://github.com/gradle/gradle/issues/26091): broader than strictly needed — a publish task
    // now waits on signing for publications it does not itself consume — but correct, and the cost is
    // paid only when signing is actually configured, which per secretOrNull's own doc is only for a
    // real Maven Central release, never for a contributor's plain publishToMavenLocal.
    tasks.withType<AbstractPublishToMaven>().configureEach {
        dependsOn(tasks.withType<Sign>())
    }
}
