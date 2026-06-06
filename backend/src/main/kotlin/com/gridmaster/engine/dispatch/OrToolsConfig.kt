package com.gridmaster.engine.dispatch

import com.google.ortools.Loader
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

/**
 * Loads OR-Tools native libraries once at application startup.
 *
 * OR-Tools ships a fat JAR with platform-specific binaries for linux-x86_64,
 * linux-aarch64, mac-x86_64, mac-aarch64, and win-x86_64. [Loader.loadNativeLibraries]
 * detects the host platform and unpacks the correct binary to a temp directory.
 *
 * This bean must initialise before any Spring component that calls
 * [com.google.ortools.linearsolver.MPSolver.createSolver].
 */
@Configuration
class OrToolsConfig {
    private val log = LoggerFactory.getLogger(OrToolsConfig::class.java)

    @PostConstruct
    fun loadNativeLibraries() {
        Loader.loadNativeLibraries()
        log.info("OR-Tools native libraries loaded successfully")
    }
}
