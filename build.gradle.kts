plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}
group = "io.github.dummyplayers"
version = "1.0.0"
repositories { mavenCentral() }
val paperHome = providers.gradleProperty("paperHome").orElse(providers.environmentVariable("PAPER_HOME"))
dependencies {
    val home = paperHome.orNull ?: error("Set -PpaperHome=<Paper 26.1.2 directory after first launch>")
    compileOnly(files("$home/versions/26.1.2/paper-26.1.2.jar"))
    compileOnly(fileTree("$home/libraries") {
        include("**/*.jar")
        exclude("io/papermc/paper/paper-api/**")
    })
    compileOnly(fileTree("$home/libraries/io/papermc/paper/paper-api") { include("26.1.2*/**/*.jar") })
    compileOnly("net.luckperms:api:5.4")
    // MySQL 连接池。MySQL 与 SQLite 驱动由 Paper 自带，slf4j 由 Paper 提供
    implementation("com.zaxxer:HikariCP:6.3.0") { exclude(group = "org.slf4j") }
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
tasks.jar { enabled = false }
tasks.shadowJar {
    archiveClassifier.set("")
    // 连接池重定位到插件自己的包下，避免与其他插件自带的版本冲突
    relocate("com.zaxxer.hikari", "io.github.dummyplayers.libs.hikari")
    dependencies { exclude(dependency("org.slf4j:.*")) }
}
tasks.build { dependsOn(tasks.shadowJar) }
