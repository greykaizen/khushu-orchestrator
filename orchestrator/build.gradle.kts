plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

kotlin { jvmToolchain(21) }

dependencies {
    // Composition layer: both family libraries cross this seam. `api` because
    // public signatures expose engine and data-api types directly.
    api("com.github.greykaizen.khushu-engine:engine-facade:2.0.0")
    api("com.github.greykaizen.khushu-data-api:khushu-data-api:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okio:okio:3.9.0") // LocalFetcher signature (data-api api-dep gap)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }

publishing {
    repositories { mavenLocal() }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.khushu"
            artifactId = "orchestrator"
            version = "1.0.0"
            pom {
                name.set("Khushu Orchestrator")
                description.set(
                    "Composition layer for the Khushu family: engine computation x " +
                        "data-api content, with the per-day model so hosts never recompute.",
                )
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
            }
        }
    }
}
