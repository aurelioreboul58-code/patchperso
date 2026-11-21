group = "crimera"

patches {
    about {
        name = "Piko Productivity"
        description = "Piko patches for Twitter/X with a finite For You feed"
        source = "https://github.com/aurelioreboul58-code/patchperso"
        author = "aurelioreboul58-code"
        contact = "na"
        website = "https://github.com/aurelioreboul58-code/patchperso"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    compileOnly("com.github.REAndroid:ARSCLib:a28c6fb2a7")

    // Used by JsonGenerator.
    implementation(libs.gson)

    implementation(libs.morphe.patches.library)
}

tasks {
    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(compileKotlin)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.resource.CheckStringKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        // The Android build converts patch classes to DEX. Morphe Manager only loads
        // patch bundles that carry those DEX entries.
        dependsOn(buildAndroid)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
