pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
	}

	plugins {
		id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
	}
}

// Should match your modid
rootProject.name = "anima-fabric"
