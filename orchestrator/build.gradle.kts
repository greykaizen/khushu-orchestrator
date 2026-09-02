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
    // Composition + serving layer: engine is the only family dependency left.
    // `api` because public signatures expose engine types directly; the
    // content-retrieval code (com.khushu.data.*) lives in THIS artifact.
    api("com.github.greykaizen.khushu-engine:engine-facade:2.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okio:okio:3.9.0")
    api("org.xerial:sqlite-jdbc:3.46.1.0") // LocalHadithRepository reads distribution .db corpora
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
            version = "1.4.0"
            pom {
                name.set("Khushu Orchestrator")
                description.set(
                    "Composition + serving layer for the Khushu family: engine computation, " +
                        "content retrieval, and the per-day model so hosts never recompute.",
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
