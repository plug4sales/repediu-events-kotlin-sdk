import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library") version "9.1.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "br.com.repediu"
version = "0.1.0"

android {
    namespace = "br.com.repediu.events"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        aarMetadata {
            minCompileSdk = 24
        }
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    explicitApi()
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.18")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(group.toString(), "repediu-events", version.toString())

    pom {
        name.set("Repediu Events")
        description.set("Repediu events SDK for Android apps. Tracks screens, cart, orders, campaigns and customer identity, with an offline queue and multi-store support.")
        inceptionYear.set("2026")
        url.set("https://github.com/plug4sales/repediu-events-kotlin-sdk")
        licenses {
            license {
                name.set("BSD-3-Clause")
                url.set("https://opensource.org/license/bsd-3-clause")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("repediu")
                name.set("Repediu")
                url.set("https://repediu.com.br")
            }
        }
        scm {
            url.set("https://github.com/plug4sales/repediu-events-kotlin-sdk")
            connection.set("scm:git:https://github.com/plug4sales/repediu-events-kotlin-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/plug4sales/repediu-events-kotlin-sdk.git")
        }
    }
}
