rootProject.name = "khushu-orchestrator"
include(":orchestrator")

// Dev mode: -PlocalFamily substitutes the JitPack coordinates with local
// source builds of the sibling repos (composite builds). Without the flag,
// coordinates resolve from JitPack as hosts consume them.
if (providers.gradleProperty("localFamily").isPresent) {
    includeBuild("../khushu-engine") {
        dependencySubstitution {
            substitute(module("com.github.greykaizen.khushu-engine:engine-facade"))
                .using(project(":engine:facade"))
            substitute(module("com.github.greykaizen:khushu-engine"))
                .using(project(":engine:facade"))
        }
    }
}
