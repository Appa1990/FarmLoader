plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "xyz.mayahive.farmloader"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    paperweight.paperDevBundle("26.3.build.214-stable")
    testImplementation("junit:junit:4.13.2")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
tasks.test { useJUnit() }
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar {
    archiveBaseName.set("FarmLoader")
    from("LICENSE")
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}
