import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	`java-library`
	id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.nine.ai.jadx"
version = System.getenv("VERSION") ?: "0.0.9"

repositories {
	mavenCentral()
	maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11
	// 优化：统一编码，避免中文乱码
	withSourcesJar() // 保留源码包（不新增功能，仅完善Java配置）
}

dependencies {
	val jadxVersion = "1.5.5"

	// 核心依赖：开发插件必须，但不打入 jar 包
	compileOnly("io.github.skylot:jadx-core:$jadxVersion")
	compileOnly("io.github.skylot:jadx-gui:$jadxVersion")
	compileOnly("com.fifesoft:rsyntaxtextarea:3.3.4")

	// 添加你需要的 HTTP 库（如 OkHttp），这个会打入最终的 shadowJar
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("ch.qos.logback:logback-classic:1.5.16")

	// 测试相关
	testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
}

tasks {
	// 优化：编译配置增强（不新增任务，仅完善原有编译逻辑）
	withType<JavaCompile> {
		options.encoding = "UTF-8" // 统一编码
	}

	// 配置 ShadowJar 任务
	withType<ShadowJar> {
		archiveClassifier.set("") // 去掉生成文件的 -all 后缀
		// 如果插件在 JADX 中运行报错，取消下面两行的注释
		// mergeServiceFiles()
		// append("META-INF/services/jadx.api.plugins.IJadxPlugin")

		// 优化：规范文件名+减小体积（无功能新增）
		archiveFileName.set("${project.name}-${version}.jar")
		exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA") // 排除无用签名文件
	}

	// 快捷任务：生成 jar 并拷贝到 build/dist
	register<Copy>("dist") {
		group = "jadx-plugin"
		dependsOn(named("shadowJar"))
		from(layout.buildDirectory.dir("libs"))
		into(layout.buildDirectory.dir("dist"))
		include("*.jar")

		// 优化：仅拷贝当前版本jar，避免旧文件残留
		include("${project.name}-${version}.jar")
	}

	test {
		useJUnitPlatform()
		// 优化：测试日志增强（不新增功能，仅完善输出）
		testLogging {
			events("PASSED", "FAILED", "SKIPPED")
			showStandardStreams = true
		}
	}
}
