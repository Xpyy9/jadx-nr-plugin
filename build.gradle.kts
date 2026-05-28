import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	`java-library`
	alias(libs.plugins.shadow)
}

group = "com.nine.ai.jadx"
version = System.getenv("VERSION") ?: "0.3.9"

repositories {
	mavenCentral()
	maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	withSourcesJar()
}

dependencies {
	compileOnly(libs.jadx.core)
	compileOnly(libs.jadx.gui)
	compileOnly(libs.rsyntaxtextarea)

	implementation(libs.logback)
	implementation(libs.gson)
	implementation(libs.snakeyaml)

	testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
}

tasks {
	withType<JavaCompile> {
		options.encoding = "UTF-8"
	}

	withType<ShadowJar> {
		archiveClassifier.set("")
		archiveFileName.set("${project.name}-${version}.jar")
		exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
	}

	register<Copy>("dist") {
		group = "jadx-plugin"
		dependsOn(named("shadowJar"))
		from(layout.buildDirectory.dir("libs"))
		into(layout.buildDirectory.dir("dist"))
		include("${project.name}-${version}.jar")
	}

	test {
		useJUnitPlatform()
		testLogging {
			events("PASSED", "FAILED", "SKIPPED")
			showStandardStreams = true
		}
	}
}
