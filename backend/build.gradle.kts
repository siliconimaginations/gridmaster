import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    id("org.springframework.boot") version "3.2.4"
    id("io.spring.dependency-management") version "1.1.4"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

group = "com.gridmaster"
version = "0.1.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}

val powsyblVersion = "6.5.0"

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // PowSyBl — physics engine
    implementation("com.powsybl:powsybl-iidm-impl:$powsyblVersion")
    implementation("com.powsybl:powsybl-iidm-reducer:$powsyblVersion")
    implementation("com.powsybl:powsybl-open-loadflow:1.9.1")
    implementation("com.powsybl:powsybl-security-analysis-api:$powsyblVersion")
    implementation("com.powsybl:powsybl-contingency-api:$powsyblVersion")

    // SQLite
    runtimeOnly("org.xerial:sqlite-jdbc:3.45.2.0")
    runtimeOnly("org.hibernate.orm:hibernate-community-dialects:6.4.4.Final")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "21"
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

ktlint {
    version.set("1.2.1")
    verbose.set(true)
    outputToConsole.set(true)
}
