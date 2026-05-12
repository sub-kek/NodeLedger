plugins {
    `java-library`
    `maven-publish`
}

group = "space.subkek"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }

    withJavadocJar()
    withSourcesJar()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val testDir = layout.projectDirectory.dir(".test").asFile
    doFirst {
        testDir.mkdirs()
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name = "NodeLedger"
                description =
                    "Append-only binary format library for tree-shaped typed data."
                url = "https://repo.subkek.space/maven-public"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/license/mit"
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "subkek"
            url = uri("https://repo.subkek.space/maven-public")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}
