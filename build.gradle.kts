plugins { java }
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
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
