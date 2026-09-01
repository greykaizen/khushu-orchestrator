rootProject.name = "khushu-orchestrator"
include(":orchestrator")

// Dev mode: -PlocalFamily substitutes the JitPack coordinates with local
// source builds of the sibling repos (composite builds). Without the flag,
// coordinates resolve from JitPack as hosts consume them.
if (providers.gradleProperty("localFamily").isPresent) {
    includeBuild("../khushu-quran-data") {
        dependencySubstitution {
            // JitPack serves the repo as group com.github.greykaizen (flattened)
            // with version = tag; substitute BOTH the flat and nested forms so
            // dev mode matches whatever the build file declares.
            substitute(module("com.github.greykaizen:khushu-data-api"))
                .using(project(":api"))
            substitute(module("com.github.greykaizen.khushu-data-api:khushu-data-api"))
                .using(project(":api"))
        }
    }
    includeBuild("../khushu-engine") {
        dependencySubstitution {
            substitute(module("com.github.greykaizen.khushu-engine:engine-facade"))
                .using(project(":engine:facade"))
            substitute(module("com.github.greykaizen:khushu-engine"))
                .using(project(":engine:facade"))
        }
    }
}
