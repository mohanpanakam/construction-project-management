// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Force all Kotlin stdlib artifacts to match the Kotlin plugin version (2.3.0)
// This resolves the "downgrade Kotlin runtime libraries" warning in Android Studio.
subprojects {
    configurations.all {
        resolutionStrategy {
            val kotlinVersion = "2.3.0"
            force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
            force("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
        }
    }
}

// ── Ops shortcuts (infra/*.sh) — exposed as Gradle tasks so they're runnable from
// the IDE's Gradle tool window / "Run" gutter icon (right-click -> Run), or from a
// terminal via `./gradlew <taskName>`, without needing any chat "/" slash-command
// support (this repo's AI chat only supports its own BUILT-IN slash commands, e.g.
// /explain /fix /tests — it can't be extended to invoke arbitrary repo shell
// scripts, so these Gradle tasks are the actual one-click/one-command entry point).
// See AGENT_NOTES.md "Redeploy workflow" for what each script does.
tasks.register<Exec>("deployToAws") {
    group = "ops"
    description = "Compiles backend+app, builds the APK, deploys the backend to AWS EC2, and validates health (infra/deploy.sh)."
    workingDir = rootDir
    commandLine("bash", "infra/deploy.sh")
}

tasks.register<Exec>("deployBackendOnly") {
    group = "ops"
    description = "Same as deployToAws but skips the Android APK build (infra/deploy.sh --skip-app)."
    workingDir = rootDir
    commandLine("bash", "infra/deploy.sh", "--skip-app")
}

tasks.register<Exec>("compileAll") {
    group = "ops"
    description = "Compiles backend+app and builds the APK locally WITHOUT touching AWS (infra/deploy.sh --skip-backend-deploy)."
    workingDir = rootDir
    commandLine("bash", "infra/deploy.sh", "--skip-backend-deploy")
}

tasks.register<Exec>("ocrLogs") {
    group = "ops"
    description = "Fetches the last 200 lines of logs from the ocr-service container on the prod EC2 instance (infra/ocr-logs.sh)."
    workingDir = rootDir
    commandLine("bash", "infra/ocr-logs.sh")
}

// Aliases matching the exact subcommand names of infra/ops.sh (build / ocr_logs /
// connect_aws) — kept separate from the camelCase tasks above (deployToAws etc.)
// so both naming styles work: `./gradlew build_` / `./infra/ops.sh build`.
// NOTE: plain `build` is deliberately NOT used as a task name here — it would
// collide with Gradle's own reserved lifecycle task name if this root project ever
// gains a plugin (Java/base) that defines one.
tasks.register<Exec>("build_") {
    group = "ops"
    description = "Alias for deployToAws — matches `./infra/ops.sh build`."
    workingDir = rootDir
    commandLine("bash", "infra/ops.sh", "build")
}

tasks.register<Exec>("ocr_logs") {
    group = "ops"
    description = "Alias for ocrLogs — matches `./infra/ops.sh ocr_logs`."
    workingDir = rootDir
    commandLine("bash", "infra/ops.sh", "ocr_logs")
}

tasks.register<Exec>("connect_aws") {
    group = "ops"
    description = "Enables SSH (if needed) and opens an interactive session to the prod EC2 instance (infra/connect-aws.sh)."
    workingDir = rootDir
    standardInput = System.`in`
    commandLine("bash", "infra/ops.sh", "connect_aws")
}

tasks.register<Exec>("db") {
    group = "ops"
    description = "Enables SSH (if needed) and opens an interactive psql session to the prod Postgres database (infra/db-connect.sh)."
    workingDir = rootDir
    standardInput = System.`in`
    commandLine("bash", "infra/ops.sh", "db")
}

