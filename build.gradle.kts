import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-velocity") version "3.1.0"
    id("eclipse")
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.4.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")

    // LimboAPI 1.2.0 (первая версия с поддержкой Minecraft 26.3) не опубликована в Maven,
    // поэтому API берётся из jar релиза на GitHub. Плагин LimboAPI ставится на прокси отдельно.
    exclusiveContent {
        forRepository {
            ivy("https://github.com/Elytrium/LimboAPI/releases/download/") {
                name = "LimboAPI GitHub Releases"
                patternLayout { artifact("[revision]/[module]-[revision].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("net.elytrium", "limboapi") }
    }
}

val velocityVersion = "4.3.1"
val limboApiVersion = "1.2.0"

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityVersion")
    compileOnly("net.elytrium:limboapi:$limboApiVersion")

    // Discord-бот. Голосовые зависимости не нужны, slf4j предоставляет Velocity.
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
        exclude(module = "tink")
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.slf4j")
    }

    // Хранилище аккаунтов в MariaDB.
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.11")

    testImplementation("com.velocitypowered:velocity-api:$velocityVersion")
    testImplementation("net.elytrium:limboapi:$limboApiVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }

    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    }

    shadowJar {
        // Перемещаем зависимости в свой пакет, чтобы не конфликтовать с другими плагинами прокси.
        val prefix = "su.prismaworld.logify.lib"
        listOf(
            "net.dv8tion.jda",
            "okhttp3",
            "okio",
            "kotlin",
            "com.fasterxml.jackson",
            "gnu.trove",
            "org.apache.commons.collections4",
            "com.neovisionaries.ws",
            "com.zaxxer.hikari",
            "org.mariadb.jdbc",
        ).forEach { relocate(it, "$prefix.$it") }

        mergeServiceFiles()
        // Файлы, которые объединяют трансформеры shadow, не должны отбрасываться как дубликаты
        filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }

    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion(velocityVersion)
        downloadPlugins {
            github("Elytrium", "LimboAPI", limboApiVersion, "limboapi-$limboApiVersion.jar")
        }
    }
}

val templateSource = file("src/main/templates")
val templateDest = layout.buildDirectory.dir("generated/sources/templates")
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    from(templateSource)
    into(templateDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateTemplates.map { it.outputs }) }

project.idea.project.settings.taskTriggers.afterSync(generateTemplates)
project.eclipse.synchronizationTasks(generateTemplates)
