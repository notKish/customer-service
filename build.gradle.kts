// Standalone customer-service provider. Extracted from the contract-testing monorepo.
// The version is the commit hash: it keys the PactFlow provider version and every verification
// result, so it must identify exactly one tree.
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example.customer"

fun git(vararg args: String): String =
    providers
        .exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput
        .asText
        .get()
        .trim()

// The version is the commit hash, nothing else: a version must identify exactly one tree. A dirty
// working tree is therefore not publishable (see the guard on publishProviderContract), rather than
// publishing a hash-suffixed version that corresponds to no commit.
val commitHash = git("rev-parse", "--short=12", "HEAD").ifEmpty { "0.0.0-unknown" }
val treeIsClean = git("status", "--porcelain").isEmpty()

// GitHub Actions turns a missing secret into an empty string, so blank has to mean "unset" here:
// otherwise the empty value wins over the fallback and pact points at "".
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

version = providers.environmentVariable("PACT_VERSION").orNull?.takeIf { it.isNotBlank() } ?: commitHash

// Recorded with the provider version. PACT_PROVIDER_BRANCH overrides it in CI, where HEAD is
// detached; otherwise the checked-out branch is used.
val providerBranch =
    env("PACT_PROVIDER_BRANCH")
        ?: git("rev-parse", "--abbrev-ref", "HEAD").takeIf { it.isNotEmpty() && it != "HEAD" }
        ?: "main"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Serves /v3/api-docs. The generated document is the provider contract published to PactFlow.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val openApiSpecFile = layout.buildDirectory.file("openapi/openapi.json")

/**
 * Generates the provider contract from the running application. Separate from `test` on purpose:
 * publishing an OpenAPI document must not depend on a consumer's pacts being in the broker.
 */
val openApiSpecTest =
    tasks.register<Test>("openApiSpecTest") {
        description = "Generates the OpenAPI document that is published as this provider's contract."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        filter { includeTestsMatching("*OpenApiSpecTest") }
        systemProperty("openapi.output", openApiSpecFile.get().asFile.absolutePath)
    }

// PactFlow validates the self-verification object: `--verification-success` on its own sends only
// {"success": true} and is rejected ("selfVerificationResults.content is missing"). Always attach
// evidence. The content is staged inside the repo because the CLI reads it in the Docker container.
val selfVerificationFile = layout.buildDirectory.file("openapi/self-verification.txt")

val stageSelfVerification =
    tasks.register("stageSelfVerification") {
        description = "Copies PACT_VERIFICATION_RESULTS into the build, or writes a self-declared note."
        val source = providers.environmentVariable("PACT_VERIFICATION_RESULTS")
        val target = selfVerificationFile.get().asFile
        // Captured as plain values: referencing project/rootDir inside doLast is a
        // configuration-cache violation.
        val repoRoot = rootDir
        outputs.file(target)
        outputs.upToDateWhen { false }
        doLast {
            val evidence = source.orNull?.takeIf { it.isNotBlank() }
            if (evidence == null) {
                target.parentFile.mkdirs()
                target.writeText(
                    """
                    Self-verification declared by the build: no API conformance tool was run
                    against this OpenAPI document.

                    Attach real evidence with PACT_VERIFICATION_RESULTS=<file> PACT_VERIFIER=<tool>
                    PACT_VERIFICATION_EXIT_CODE=<exit code>.
                    """.trimIndent(),
                )
            } else {
                val given = File(evidence)
                val evidenceFile = if (given.isAbsolute) given else File(repoRoot, evidence)
                check(evidenceFile.isFile) { "PACT_VERIFICATION_RESULTS is not a file: $evidenceFile" }
                target.writeBytes(evidenceFile.readBytes())
            }
        }
    }

tasks.register<Exec>("publishProviderContract") {
    description = "Publishes the generated OpenAPI document to PactFlow (bi-directional contract testing)."
    group = "verification"
    dependsOn(openApiSpecTest, stageSelfVerification)

    // Locals captured by value: referring to the script-level vals inside doFirst would capture the
    // Gradle script object and break configuration-cache serialization.
    val cleanForPublish = treeIsClean
    val hashForPublish = commitHash
    doFirst {
        check(cleanForPublish) {
            "Working tree is dirty: commit first so the provider version is a real commit hash " +
                "($hashForPublish). Uncommitted changes are not publishable."
        }
    }

    // The CLI runs in Docker so no Ruby toolchain is required. The broker URL and token are passed
    // through from the environment, never baked into the build.
    val specPath = openApiSpecFile.get().asFile.relativeTo(rootDir).path
    val resultsPath = selfVerificationFile.get().asFile.relativeTo(rootDir).path
    val verifier = env("PACT_VERIFIER") ?: "manual"
    val resultsContentType = env("PACT_VERIFICATION_RESULTS_CONTENT_TYPE") ?: "text/plain"
    val verificationExitCode = env("PACT_VERIFICATION_EXIT_CODE") ?: "0"
    commandLine(
        "docker", "run", "--rm",
        "-v", "${rootDir.absolutePath}:/workspace",
        "-w", "/workspace",
        "-e", "PACT_BROKER_BASE_URL",
        "-e", "PACT_BROKER_TOKEN",
        "-e", "PACT_BROKER_USERNAME",
        "-e", "PACT_BROKER_PASSWORD",
        "pactfoundation/pact-cli",
        "pactflow", "publish-provider-contract", specPath,
        "--provider", "customer-service",
        "--provider-app-version", project.version.toString(),
        "--branch", providerBranch,
        "--specification", "oas",
        "--content-type", "application/json",
        // --verification-exit-code and --verification-success are mutually exclusive.
        "--verification-exit-code", verificationExitCode,
        "--verification-results", resultsPath,
        "--verification-results-content-type", resultsContentType,
        "--verifier", verifier,
    )
}
