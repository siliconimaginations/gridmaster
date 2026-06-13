import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
    id("org.springframework.boot") version "3.2.4"
    id("io.spring.dependency-management") version "1.1.4"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    jacoco
}

group = "com.gridmaster"
version = "0.1.0-SNAPSHOT"
java {
    // Pin JVM to Temurin 21 — toolchain replaces sourceCompatibility + jvmTarget settings.
    // Gradle will use the JDK installed by CI (actions/setup-java temurin-21) or auto-provision locally.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

kotlin {
    // Explicitly align the Kotlin compiler target with the Java toolchain declared above.
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JWT — jjwt 0.12.x
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // PowSyBl — physics engine (versions managed by BOM)
    implementation(platform("com.powsybl:powsybl-dependencies:2025.0.2"))
    implementation("com.powsybl:powsybl-iidm-impl")
    implementation("com.powsybl:powsybl-iidm-reducer")
    implementation("com.powsybl:powsybl-open-loadflow")
    implementation("com.powsybl:powsybl-security-analysis-api")
    implementation("com.powsybl:powsybl-contingency-api")
    implementation("com.powsybl:powsybl-iidm-serde")
    // IEEE CDF network factory — not in powsybl-dependencies BOM, pinned to core version
    implementation("com.powsybl:powsybl-ieee-cdf-converter:6.7.2")

    // Coroutines — NetworkRepository methods are suspend; IO wrapping in SqliteNetworkRepository
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // Required by Spring MVC to invoke suspend controller functions via CoroutineInvocableHandlerMethod
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("com.google.ortools:ortools-java:9.10.4067")
    // SQLite
    runtimeOnly("org.xerial:sqlite-jdbc:3.45.2.0")
    runtimeOnly("org.hibernate.orm:hibernate-community-dialects:6.4.4.Final")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.powsybl:powsybl-iidm-test")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    // Integration tests (hitting real PowSyBl solver) are tagged "integration"
    // Run them separately: ./gradlew test -Pintegration
    useJUnitPlatform {
        if (!project.hasProperty("integration")) {
            excludeTags("integration")
        }
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    // Always run after tests; CI reads the XML for the coverage comment.
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = true // needed by cicirello/jacoco-badge-generator
    }
    // Exclude generated/config classes from coverage metrics.
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/Application*",
                        "**/config/**",
                        "**/persistence/*Entity*",
                        "**/persistence/*JpaRepository*",
                    )
                }
            },
        ),
    )
}

ktlint {
    version.set("1.2.1")
    verbose.set(true)
    outputToConsole.set(true)
}

