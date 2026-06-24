plugins {
    `kotlin-dsl`
    `maven-publish`
}

val PUBLISH_GROUP_ID: String by project
val PUBLISH_ARTIFACT_ID: String by project
val PUBLISH_VERSION: String by project

group = PUBLISH_GROUP_ID
version = PUBLISH_VERSION

base {
    archivesName.set(PUBLISH_ARTIFACT_ID)
}

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://plugins.gradle.org/m2/") }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(gradleApi())
    implementation("org.javassist:javassist:3.30.2-GA")
    // Compile against AGP 8.3.0 but resolve AGP from the consuming project at runtime,
    // so the plugin works with any AGP from 8.x up to the latest (9.x).
    compileOnly("com.android.tools.build:gradle:8.3.0")
    compileOnly("com.android.tools:common:31.3.0")
    implementation("org.ow2.asm:asm-commons:9.9.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        create("fatAar") {
            id = "com.kezong.fat-aar"
            implementationClass = "com.kezong.fataar.FatAarPlugin"
        }
    }
}

// Configure the auto-generated pluginMaven publication for jitpack/maven.
afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = PUBLISH_ARTIFACT_ID

            pom {
                name.set(PUBLISH_ARTIFACT_ID)
                description.set(
                    "A gradle plugin that merges dependencies into the final aar file, works with AGP 8.x-9.x"
                )
                url.set("https://github.com/abdallahFc/fat-aar-android")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("abdallahFc")
                        name.set("abdallahFc")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/abdallahFc/fat-aar-android.git")
                    developerConnection.set("scm:git:ssh://github.com/abdallahFc/fat-aar-android.git")
                    url.set("https://github.com/abdallahFc/fat-aar-android/tree/master")
                }
            }
        }
    }
}
