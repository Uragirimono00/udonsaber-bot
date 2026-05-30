plugins {
    application
    java
}

group = "com.udonsaber"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.5.1") {
        exclude(module = "opus-java")
    }
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

application {
    mainClass = "com.udonsaber.bot.urasaber.UraSaberMain"
    // 산출물 이름을 udonsaber-bot 으로 유지 — 기존 배포 파이프라인(deploy.yml)과
    // 서버 systemd ExecStart(build/install/udonsaber-bot/bin/udonsaber-bot) 경로 그대로 재사용.
    applicationName = "udonsaber-bot"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
}
